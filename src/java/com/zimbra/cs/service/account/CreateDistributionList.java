/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2011, 2012, 2013 Zimbra Software, LLC.
 * 
 * The contents of this file are subject to the Zimbra Public License
 * Version 1.4 ("License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 * http://www.zimbra.com/license.
 * 
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * ***** END LICENSE BLOCK *****
 */

package com.zimbra.cs.service.account;

import java.util.Map;

import com.zimbra.common.service.ServiceException;
import com.zimbra.common.soap.AccountConstants;
import com.zimbra.common.soap.Element;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.account.AccessManager;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.Group;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.account.ldap.entry.LdapDistributionList;
import com.zimbra.cs.account.ldap.entry.LdapDynamicGroup;
import com.zimbra.soap.ZimbraSoapContext;

public class CreateDistributionList extends AccountDocumentHandler {

    public Element handle(Element request, Map<String, Object> context)
            throws ServiceException {

        ZimbraSoapContext zsc = getZimbraSoapContext(context);
        Provisioning prov = Provisioning.getInstance();
        Account acct = getAuthenticatedAccount(zsc);

        String name = request.getAttribute(AccountConstants.E_NAME).toLowerCase();

        if (!AccessManager.getInstance().canCreateGroup(acct, name)) {
            throw ServiceException.PERM_DENIED("you do not have sufficient rights to create distribution list");
        }

        Map<String, Object> attrs = AccountService.getKeyValuePairs(
                request, AccountConstants.E_A, AccountConstants.A_N);

        boolean dynamic = request.getAttributeBool(AccountConstants.A_DYNAMIC, true);

        // creator of the group will automatically become the first owner of the group
        Account creator = getAuthenticatedAccount(zsc);
        Group group = prov.createDelegatedGroup(name, attrs, dynamic, creator);

        ZimbraLog.security.info(ZimbraLog.encodeAttrs(
                 new String[] { "cmd", "CreateDistributionList", "name", name }, attrs));

        Element response = zsc.createElement(AccountConstants.CREATE_DISTRIBUTION_LIST_RESPONSE);
        Element eDL = response.addElement(AccountConstants.E_DL);
        eDL.addAttribute(AccountConstants.A_NAME, group.getName());
        if (group.isDynamic()) {
            eDL.addAttribute(AccountConstants.A_REF, ((LdapDynamicGroup) group).getDN());
        } else {
            eDL.addAttribute(AccountConstants.A_REF, ((LdapDistributionList) group).getDN());
        }
        eDL.addAttribute(AccountConstants.A_ID, group.getId());
        GetDistributionList.encodeAttrs(group, eDL, null);

        return response;
    }

}