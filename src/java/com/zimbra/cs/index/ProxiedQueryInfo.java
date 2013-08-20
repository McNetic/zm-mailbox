/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2006, 2007, 2009, 2010, 2013 Zimbra Software, LLC.
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
package com.zimbra.cs.index;

import com.zimbra.common.soap.Element;

public class ProxiedQueryInfo implements QueryInfo {

    private Element mElt;

    ProxiedQueryInfo(Element e) {
        mElt = e;
        mElt.detach();
    }

    public Element toXml(Element parent) {
        parent.addElement(mElt);
        return mElt;
    }

    @Override
    public String toString() {
        return mElt.toString();
    }

}
