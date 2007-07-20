/*
 * ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1
 * 
 * The contents of this file are subject to the Mozilla Public License
 * Version 1.1 ("License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.zimbra.com/license
 * 
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
 * the License for the specific language governing rights and limitations
 * under the License.
 * 
 * The Original Code is: Zimbra Collaboration Suite Server.
 * 
 * The Initial Developer of the Original Code is Zimbra, Inc.
 * Portions created by Zimbra are Copyright (C) 2004, 2005, 2006 Zimbra, Inc.
 * All Rights Reserved.
 * 
 * Contributor(s): 
 * 
 * ***** END LICENSE BLOCK *****
 */

package com.zimbra.cs.account.ldap;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.naming.NameAlreadyBoundException;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;

import com.zimbra.common.localconfig.LC;
import com.zimbra.common.service.ServiceException;
import com.zimbra.common.util.EmailUtil;
import com.zimbra.common.util.Log;
import com.zimbra.common.util.LogFactory;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.AccountServiceException;
import com.zimbra.cs.account.Alias;
import com.zimbra.cs.account.DistributionList;
import com.zimbra.cs.account.Domain;
import com.zimbra.cs.account.NamedEntry;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.account.Provisioning.CacheEntryType;
import com.zimbra.cs.account.Server;
import com.zimbra.cs.account.Provisioning.CacheEntry;
import com.zimbra.cs.account.Provisioning.CacheEntryBy;
import com.zimbra.cs.account.soap.SoapProvisioning;
import com.zimbra.cs.servlet.ZimbraServlet;


class RenameDomain {
 
    private static final Log sRenameDomainLog = LogFactory.getLog("zimbra.provisioning.renamedomain");
    
    private DirContext mDirCtxt;
    private LdapProvisioning mProv;
    private Domain mOldDomain;
    private String mNewDomainName;
    
    RenameDomain(DirContext dirCtxt, LdapProvisioning prov, Domain oldDomain, String newDomainName) {
        mDirCtxt = dirCtxt;
        mProv = prov;
        mOldDomain = oldDomain;
        mNewDomainName = newDomainName;
    }
    
    private RenameDomainVisitor getVisitor(RenameDomainVisitor.Phase phase) {
        return new RenameDomainVisitor(mDirCtxt, mProv, mOldDomain.getName(), mNewDomainName, phase);
    }
        
    
    public void execute() throws ServiceException {
        String oldDomainName = mOldDomain.getName();
        String oldDomainId = mOldDomain.getId();
           
        beginRenameDomain();

            
        /*
         * 1. create the new domain
         */ 
        // Get existing domain attributes
        // make a copy, we don't want to step over our old domain object
        Map<String, Object> domainAttrs = new HashMap<String, Object>(mOldDomain.getAttrs(false));
        
        // remove attributes that are not needed for createDomain
        domainAttrs.remove(Provisioning.A_o);
        domainAttrs.remove(Provisioning.A_dc);
        domainAttrs.remove(Provisioning.A_objectClass);
        domainAttrs.remove(Provisioning.A_zimbraId);  // use a new zimbraId so getDomainById of the old domain will not return this half baked domain
        domainAttrs.remove(Provisioning.A_zimbraDomainName);
        domainAttrs.remove(Provisioning.A_zimbraMailStatus);
        // domainAttrs.remove(Provisioning.A_zimbraDomainStatus); // the new domain is created locked, TODO
        
        // TODO.  if the new domain exists, make sure it is the new domain 
        Domain newDomain = mProv.createDomain(mNewDomainName, domainAttrs);

            
        /*
         * 2. move all accounts, DLs, and aliases
         */ 
        RenameDomainVisitor visitor;
        String searchBase = mProv.mDIT.domainDNToAccountSearchDN(((LdapDomain)mOldDomain).getDN());
        int flags = 0;
            
        // first phase, go thru DLs and accounts and their aliases that are in the old domain into the new domain
        visitor = getVisitor(RenameDomainVisitor.Phase.PHASE_RENAME_ENTRIES);
        flags = Provisioning.SA_ACCOUNT_FLAG + Provisioning.SA_CALENDAR_RESOURCE_FLAG + Provisioning.SA_DISTRIBUTION_LIST_FLAG;
        mProv.searchObjects(null, null, searchBase, flags, visitor, 0);
            
        // second phase, go thru aliases that have not been moved yet, by now aliases left in the domain should be aliases with target in other domains
        visitor = getVisitor(RenameDomainVisitor.Phase.PHASE_FIX_FOREIGN_ALIASES);
        flags = Provisioning.SA_ALIAS_FLAG;
        mProv.searchObjects(null, null, searchBase, flags, visitor, 0);
            
        // third phase, go thru DLs and accounts in the *new* domain, rename the addresses in all DLs
        //     - the addresses to be renamed are: the DL/account's main address and all the aliases that were moved to the new domain
        //     - by now the DLs to modify should be those in other domains, because members of DLs in the old domain (now new domain) 
        //       have been updated in first pass.
        visitor = getVisitor(RenameDomainVisitor.Phase.PHASE_FIX_FOREIGN_DL_MEMBERS);
        searchBase = mProv.mDIT.domainDNToAccountSearchDN(((LdapDomain)newDomain).getDN());
        flags = Provisioning.SA_ACCOUNT_FLAG + Provisioning.SA_CALENDAR_RESOURCE_FLAG + Provisioning.SA_DISTRIBUTION_LIST_FLAG;
        mProv.searchObjects(null, null, searchBase, flags, visitor, 0);
            
        /*
         * 3. Delete the old domain
         */ 
        mProv.deleteDomain(oldDomainId);
            
        /*
         * 4. restore zimbraId to the id of the old domain and make the new domain available
         */ 
        flushDomainCache(mProv, newDomain.getId());
        HashMap<String, Object> attrs = new HashMap<String, Object>();
        attrs.put(Provisioning.A_zimbraId, oldDomainId);
        // attrs.put(A_zimbraDomainStatus, TODO);
        // attrs.put(A_zimbraDomainRenameInfo, TODO);
        mProv.modifyAttrsInternal(newDomain, mDirCtxt, attrs);  // skip callback
    }
        
    private void beginRenameDomain() throws ServiceException {
        
        /*
         * Lock the old domain and mark it being renamed   TODO
         */ 
        // attrs.put(A_zimbraDomainStatus, TODO);
        // attrs.put(A_zimbraDomainRenameInfo, TODO);
        
        flushDomainCacheOnAllServers(mOldDomain.getId());
    }
    
    private void flushDomainCache(Provisioning prov, String domainId) throws ServiceException {
        
        CacheEntry[] cacheEntries = new CacheEntry[1];
        cacheEntries[0] = new CacheEntry(CacheEntryBy.id, domainId);
        prov.flushCache(CacheEntryType.domain, cacheEntries);
    }
    
    private void flushDomainCacheOnAllServers(String domainId) throws ServiceException {
        SoapProvisioning soapProv = new SoapProvisioning();
        
        for (Server server : mProv.getAllServers()) {
            
            String hostname = server.getAttr(Provisioning.A_zimbraServiceHostname);
                                
            int port = server.getIntAttr(Provisioning.A_zimbraMailSSLPort, 0);
            if (port <= 0) {
                warn("flushDomainCacheOnAllServers: remote server " + server.getName() + " does not have https port enabled, domain cache not flushed on server");
                continue;        
            }
                
            soapProv.soapSetURI(LC.zimbra_admin_service_scheme.value()+hostname+":"+port+ZimbraServlet.ADMIN_SERVICE_URI);
                
            try {
                soapProv.soapZimbraAdminAuthenticate();
                flushDomainCache(soapProv, domainId);
            } catch (ServiceException e) {
                warn("flushDomainCacheOnAllServers: domain cache not flushed on server " + server.getName(), e);
            }
        }
    }
   
    static class RenameDomainVisitor implements NamedEntry.Visitor {
    
        private DirContext mDirCtxt;
        private LdapProvisioning mProv;
        private String mOldDomainName;
        private String mNewDomainName;
        private Phase mPhase;
    
        public static enum Phase {
            /*
             * Note: the following text is written in zimbraDomainRenameInfo - if changed needs migration.
             */
            PHASE_RENAME_ENTRIES,
            PHASE_FIX_FOREIGN_ALIASES,
            PHASE_FIX_FOREIGN_DL_MEMBERS
        }
    
        private static final String[] sDLAttrsNeedRename = {Provisioning.A_mail, 
                                                            Provisioning.A_zimbraMailAlias,
                                                            Provisioning.A_zimbraMailForwardingAddress,
                                                            Provisioning.A_zimbraMailDeliveryAddress, // ?
                                                            Provisioning.A_zimbraMailCanonicalAddress};
    
        private static final String[] sAcctAttrsNeedRename = {Provisioning.A_mail, 
                                                              Provisioning.A_zimbraMailAlias,
                                                              Provisioning.A_zimbraMailForwardingAddress,
                                                              Provisioning.A_zimbraMailDeliveryAddress, // ?
                                                              Provisioning.A_zimbraMailCanonicalAddress};
    
    
        
    
        private RenameDomainVisitor(DirContext dirCtxt, LdapProvisioning prov, String oldDomainName, String newDomainName, Phase phase) {
            mDirCtxt = dirCtxt;
            mProv = prov;
            mOldDomainName = oldDomainName;
            mNewDomainName = newDomainName;
            mPhase = phase;
        }
    
        public void visit(NamedEntry entry) throws ServiceException {
            debug("(" + mPhase.name() + ") visiting " + entry.getName());
        
            if (mPhase == Phase.PHASE_RENAME_ENTRIES) {
                if (entry instanceof DistributionList)
                    handleEntry(entry, true);  // PHASE_RENAME_ENTRIES
                else if (entry instanceof Account)
                    handleEntry(entry, false); // PHASE_RENAME_ENTRIES
            } else if (mPhase == Phase.PHASE_FIX_FOREIGN_ALIASES) {
                if (entry instanceof Alias)
                    handleForeignAlias(entry); // PHASE_FIX_FOREIGN_ALIASES
                else
                    assert(false);  // by now there should only be foreign aliases in the old domain
            } else if (mPhase == Phase.PHASE_FIX_FOREIGN_DL_MEMBERS) {
                handleForeignDLMembers(entry);
            }
        }
    
        private void handleEntry(NamedEntry entry, boolean isDL) {
            LdapEntry ldapEntry = (LdapEntry)entry;
            String[] parts = EmailUtil.getLocalPartAndDomain(entry.getName());
            
            String newDn = null;
        
            try {
                newDn = (isDL)?mProv.mDIT.distributionListDNRename(ldapEntry.getDN(), parts[0], mNewDomainName):
                               mProv.mDIT.accountDNRename(ldapEntry.getDN(), parts[0], mNewDomainName);
            } catch (NamingException e) {
                warn(e, "handleEntry", "cannot get new DN, entry not handled", "entry=[%s]", entry.getName());
                return;
            } catch (ServiceException e) {
                warn(e, "handleEntry", "cannot get new DN, entry not handled", "entry=[%s]", entry.getName());
                return;
            }
        
            // Step 1. move the all aliases of the entry that are in the old domain to the new domain 
            String[] aliases = (isDL)?((DistributionList)entry).getAliases():((Account)entry).getAliases();
            handleAliases(entry, aliases, newDn);
         
            // Step 2. move the entry to the new domain and fixup all the addr attrs that contain the old domain
            String oldDn = ((LdapEntry)entry).getDN();
            if (isDL) 
                handleDistributionList(entry, oldDn, newDn);
            else
                handleAccount(entry, oldDn, newDn);
        }
        
        /*
         * 
         */
        private void handleAliases(NamedEntry targetEntry, String[] aliases, String newTargetDn) {
            LdapEntry ldapEntry = (LdapEntry)targetEntry;
            String oldDn = ldapEntry.getDN();
        
            // move aliases in the old domain if there are any
            for (int i=0; i<aliases.length; i++) {
            
                // for dl, the main dl addr is also in the zimbraMailAlias.  To be consistent witha account,
                // we don't move that when we move aliases; and will move it when we move the entry itself
                if (aliases[i].equals(targetEntry.getName()))
                    continue;
            
                String[] parts = EmailUtil.getLocalPartAndDomain(aliases[i]);
                if (parts == null) {
                    assert(false);
                    warn("moveEntry", "encountered invalid alias address", "alias=[%s], entry=[%s]", aliases[i], targetEntry.getName());
                    continue;
                }
                String aliasLocal = parts[0];
                String aliasDomain = parts[1];
                if (aliasDomain.equals(mOldDomainName)) {
                    // move the alias
                    // ug, aliasDN and aliasDNRename also throw ServiceExeption - declared vars outside the try block so we can log it in the catch blocks
                    String oldAliasDn = "";  
                    String newAliasDn = "";
                    try {
                        oldAliasDn = mProv.mDIT.aliasDN(oldDn, mOldDomainName, aliasLocal, mOldDomainName);
                        newAliasDn = mProv.mDIT.aliasDNRename(newTargetDn, mNewDomainName, aliasLocal+"@"+mNewDomainName);
                        if (!oldAliasDn.equals(newAliasDn))
                            LdapUtil.renameEntry(mDirCtxt, oldAliasDn, newAliasDn);
                    } catch (NamingException e) {
                        // log the error and continue
                        warn(e, "moveEntry", "alias not moved", "alias=[%s], entry=[%s], oldAliasDn=[%s], newAliasDn=[%s]", aliases[i], targetEntry.getName(), oldAliasDn, newAliasDn);
                    } catch (ServiceException e) {
                        // log the error and continue
                        warn(e, "moveEntry", "alias not moved", "alias=[%s], entry=[%s], oldAliasDn=[%s], newAliasDn=[%s]", aliases[i], targetEntry.getName(), oldAliasDn, newAliasDn);
                    }
                }
            }
        }
    
        private void handleDistributionList(NamedEntry entry, String oldDn, String newDn) {
        
            NamedEntry refreshedEntry = entry;
        
            if (!oldDn.equals(newDn)) {
                // move the entry
                try {
                    LdapUtil.renameEntry(mDirCtxt, oldDn, newDn);
                } catch (NamingException e) {
                    warn(e, "moveDistributionList", "renameEntry", "entry=[%s], oldDn=[%s], newDn=[%s]", entry.getName(), oldDn, newDn);
                }
            
                // refresh for the new DN
                // do not catch here, if we can't refresh - we can't modify, just let it throw and proceed to the next entry
                try {
                    refreshedEntry = mProv.get(Provisioning.DistributionListBy.id, entry.getId());
                } catch (ServiceException e) {
                    warn(e, "moveDistributionList", "getDistributionListById, entry not modified", "entry=[%s], oldDn=[%s], newDn=[%s]", entry.getName(), oldDn, newDn);
                    // if we can't refresh - we can't modify, just return and proceed to the next entry
                    return;
                }
            }
        
            // modify the entry in the new domain
            Map<String, Object> fixedAttrs = fixupAddrs(entry, sDLAttrsNeedRename);
        
            // modify the entry
            try {
                mProv.modifyAttrsInternal(refreshedEntry, mDirCtxt, fixedAttrs);
            } catch (ServiceException e) {
                warn(e, "moveDistributionList", "modifyAttrsInternal", "entry=[%s], oldDn=[%s], newDn=[%s]", entry.getName(), oldDn, newDn);
            }
        }
    
        private void handleAccount(NamedEntry entry, String oldDn, String newDn) {
        
            NamedEntry refreshedEntry = entry;
            Map<String, Object> fixedAttrs = fixupAddrs(entry, sAcctAttrsNeedRename);
        
            if (!oldDn.equals(newDn)) {
                // move the entry
            
                /*
                 * for accounts, we need to first crate the entry in the new domain, because it may have sub entries
                 * (identities/datasources, signatures).  We create the account entry in the new domain using the fixed adddr attrs.
                 */
                Attributes attributes = new BasicAttributes(true);
                LdapUtil.mapToAttrs(fixedAttrs, attributes);
                try {
                    LdapUtil.createEntry(mDirCtxt, newDn, attributes, "renameDomain-createAccount");
                } catch (NameAlreadyBoundException e) {
                    warn(e, "moveAccount", "createEntry", "entry=[%s], oldDn=[%s], newDn=[%s]", entry.getName(), oldDn, newDn);
                } catch (ServiceException e) {
                    warn(e, "moveAccount", "createEntry", "entry=[%s], oldDn=[%s], newDn=[%s]", entry.getName(), oldDn, newDn);
                }
                    
                try {
                    // move over all identities/sources/signatures etc. doesn't throw an exception, just logs
                    LdapUtil.moveChildren(mDirCtxt, oldDn, newDn);
                } catch (ServiceException e) {
                    warn(e, "moveAccount", "moveChildren", "entry=[%s], oldDn=[%s], newDn=[%s]", entry.getName(), oldDn, newDn);
                }
                
                try {
                    LdapUtil.unbindEntry(mDirCtxt, oldDn);
                } catch (NamingException e) {
                    warn(e, "moveAccount", "unbindEntry", "entry=[%s], oldDn=[%s], newDn=[%s]", entry.getName(), oldDn, newDn);
                }
            } else {
                // didn't need to move the account entry, still need to fixup the addr attrs
         
                try {
                    mProv.modifyAttrsInternal(refreshedEntry, mDirCtxt, fixedAttrs);
                } catch (ServiceException e) {
                    warn(e, "moveAccount", "modifyAttrsInternal", "entry=[%s], oldDn=[%s], newDn=[%s]", entry.getName(), oldDn, newDn);
                }
            }
        }
    
        private Map<String, Object> fixupAddrs(NamedEntry entry, String[] attrsNeedRename)  {

            // replace the addr attrs
            Map<String, Object> attrs = entry.getAttrs(false);
            for (String attr : attrsNeedRename) {
                String[] values = entry.getMultiAttr(attr, false);
                if (values.length > 0) {
                    Set<String> newValues = new HashSet<String>();
                    for (int i=0; i<values.length; i++) {
                        String newValue = convertToNewAddr(values[i], mOldDomainName, mNewDomainName);
                        if (newValue != null)
                            newValues.add(newValue);
                    }
            
                    // replace the attr with the new values
                    attrs.put(attr, newValues.toArray(new String[newValues.size()]));
                }
            }
        
            return attrs;
        }

        /*
         * given an email address, and old domain name and a new domain name, returns:
         *   - if the domain of the address is the same as the old domain, returns localpart-of-addr@new-domain
         *   - otherwise returns the email addr as is.
         */
        private String convertToNewAddr(String addr, String oldDomain, String newDomain) {
            String[] parts = EmailUtil.getLocalPartAndDomain(addr);
            if (parts == null) {
                assert(false);
                warn("convertToNewAddr", "encountered invalid address", "addr=[%s]", addr);
                return null;
            }
                
            String local = parts[0];
            String domain = parts[1];
            if (domain.equals(oldDomain))
                return local + "@" + newDomain;
            else
                return addr;
        }
    
        /*
         * aliases in the old domain with target in other domains
         */
        private void handleForeignAlias(NamedEntry entry) {
            Alias alias = (Alias)entry;
            NamedEntry targetEntry = null;
            try {
                targetEntry = alias.searchTarget(false);
            } catch (ServiceException e) {
                warn(e, "handleForeignAlias", "target entry not found for alias" + "alias=[%s], target=[%s]", alias.getName(), targetEntry.getName());
                return;
            }
            
            // sanity check that the target is indeed in a different domain
            String targetName = targetEntry.getName();
            String[] targetParts = EmailUtil.getLocalPartAndDomain(targetName);
            if (targetParts == null) {
                warn("handleForeignAlias", "encountered invalid alias target address", "target=[%s]", targetName);
                return;
            }
            String targetDomain = targetParts[1];
            if (!targetDomain.equals(mOldDomainName)) {
                String aliasOldAddr = alias.getName();
                String[] aliasParts = EmailUtil.getLocalPartAndDomain(aliasOldAddr);
                if (aliasParts == null) {
                    warn("handleForeignAlias", "encountered invalid alias address", "alias=[%s]", aliasOldAddr);
                    return;
                }
                String aliasLocal = aliasParts[0];
                String aliasNewAddr = aliasLocal + "@" + mNewDomainName;
                if (targetEntry instanceof DistributionList) {
                    DistributionList dl = (DistributionList)targetEntry;
                    fixupForeignTarget(dl, aliasOldAddr, aliasNewAddr);
                } else if (targetEntry instanceof Account){
                    Account acct = (Account)targetEntry;
                    fixupForeignTarget(acct, aliasOldAddr, aliasNewAddr);
                } else {
                    warn("handleForeignAlias", "encountered invalid alias target type", "target=[%s]", targetName);
                    return;
                }
            }
        }
    
        private void fixupForeignTarget(DistributionList targetEntry, String aliasOldAddr,  String aliasNewAddr) {
            try {
                mProv.removeAlias(targetEntry, aliasOldAddr);
            } catch (ServiceException e) {
                warn("fixupTargetInOtherDomain", "cannot remove alias for dl" + "dl=[%s], aliasOldAddr=[%s], aliasNewAddr=[%s]", targetEntry.getName(), aliasOldAddr, aliasNewAddr);
            }
            
            // continue doing add even if remove failed 
            try {
                mProv.addAlias(targetEntry, aliasNewAddr);
            } catch (ServiceException e) {
                warn("fixupTargetInOtherDomain", "cannot add alias for dl" + "dl=[%s], aliasOldAddr=[%s], aliasNewAddr=[%s]", targetEntry.getName(), aliasOldAddr, aliasNewAddr);
            }
        }
        
        private void fixupForeignTarget(Account targetEntry, String aliasOldAddr,  String aliasNewAddr) {
            try {
                mProv.removeAlias(targetEntry, aliasOldAddr);
            } catch (ServiceException e) {
                warn("fixupTargetInOtherDomain", "cannot remove alias for account" + "acct=[%s], aliasOldAddr=[%s], aliasNewAddr=[%s]", targetEntry.getName(), aliasOldAddr, aliasNewAddr);
            }
            
            // we want to continue doing add even if remove failed 
            try {
                mProv.addAlias(targetEntry, aliasNewAddr);
            } catch (ServiceException e) {
                warn("fixupTargetInOtherDomain", "cannot add alias for account" + "acct=[%s], aliasOldAddr=[%s], aliasNewAddr=[%s]", targetEntry.getName(), aliasOldAddr, aliasNewAddr);
            }
        }
    
        /*
         * replace "old addrs of DL/accounts and their aliases that are members of DLs in other domains" to the new addrs
         */
        private void handleForeignDLMembers(NamedEntry entry) {
            Map<String, String> changedPairs = new HashMap<String, String>();
            
            String entryAddr = entry.getName();
            String[] oldNewPair = changedAddrPairs(entryAddr);
            if (oldNewPair != null)
                changedPairs.put(oldNewPair[0], oldNewPair[1]);
            
            String[] aliasesAddrs = entry.getMultiAttr(Provisioning.A_zimbraMailAlias, false);
            for (String aliasAddr : aliasesAddrs) {
                oldNewPair = changedAddrPairs(aliasAddr);
                if (oldNewPair != null)
                    changedPairs.put(oldNewPair[0], oldNewPair[1]);
            }
    
            mProv.renameAddressesInAllDistributionLists(changedPairs);
        }
        
        private String[] changedAddrPairs(String addr) {
            String[] parts = EmailUtil.getLocalPartAndDomain(addr);
            if (parts == null) {
                warn("changedAddrPairs", "encountered invalid address", "addr=[%s]", addr);
                return null;
            }
            
            String domain = parts[1];
            if (!domain.equals(mNewDomainName))
                return null;
            
            String localPart = parts[0];
            String[] oldNewAddrPairs = new String[2];
            oldNewAddrPairs[0] = localPart + "@" + mOldDomainName; 
            oldNewAddrPairs[1] = localPart + "@" + mNewDomainName; 
            
            return oldNewAddrPairs;
        }

    }
    
    private static void warn(Object o) {
        sRenameDomainLog.warn(o);
    }
    
    private static void warn(Object o, Throwable t) {
        sRenameDomainLog.warn(o, t);
    }
    
    private static void warn(String funcName, String desc, String format, Object ... objects) {
        warn(null, funcName,  desc,  format, objects);
    }

    private static void warn(Throwable t, String funcName, String desc, String format, Object ... objects) {
        if (sRenameDomainLog.isWarnEnabled())
            // mRenameDomainLog.warn(String.format(funcName + "(" + desc + "):" + format, objects), t);
            sRenameDomainLog.warn(String.format(funcName + "(" + desc + "):" + format, objects));
    }

    private static void debug(String format, Object ... objects) {
        if (sRenameDomainLog.isDebugEnabled())
            sRenameDomainLog.debug(String.format(format, objects));
    }

    private static void debug(String funcName, String desc, String format, Object ... objects) {
        debug(null, funcName,  desc,  format, objects);
    }

    private static void debug(Throwable t, String funcName, String desc, String format, Object ... objects) {
        if (sRenameDomainLog.isDebugEnabled())
            // mRenameDomainLog.warn(String.format(funcName + "(" + desc + "):" + format, objects), t);
            sRenameDomainLog.debug(String.format(funcName + "(" + desc + "):" + format, objects));
    }
    
}