/*************************************************************************
 * Copyright 2015 Adobe Systems Incorporated
 * All Rights Reserved.
 *
 * NOTICE:  Adobe permits you to use, modify, and distribute this file in accordance with the 
 * terms of the Adobe license agreement accompanying it.  If you have received this file from a 
 * source other than Adobe, then your use, modification, or distribution of it requires the prior 
 * written permission of Adobe.
 **************************************************************************/

package com.adobe.cq.social.cleanup_srp.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.jcr.RepositoryException;
import javax.servlet.ServletException;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestParameter;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.adobe.cq.social.srp.SocialResource;
import com.adobe.cq.social.srp.SocialResourceProvider;
import com.adobe.cq.social.srp.config.SocialResourceConfiguration;
import com.adobe.cq.social.ugc.api.SearchResults;
import com.adobe.cq.social.ugc.api.UgcFilter;
import com.adobe.cq.social.ugc.api.UgcSearch;
import com.adobe.cq.social.ugcbase.SocialUtils;

public class TestFixForumAuthorInfoServlet {
    private FixForumAuthorInfoServlet servlet = new FixForumAuthorInfoServlet();
    private SocialUtils socialUtils = Mockito.mock(SocialUtils.class);
    private UgcSearch ugcSearch = Mockito.mock(UgcSearch.class);
    private static final String asiPath = "/content/usergenerated/asi/cloud";

    /**
     * Setup for tests.
     * @throws IllegalAccessException on failure
     */
    @Before
    public void setUp() throws IllegalAccessException {
        FieldUtils.writeField(servlet, "socialUtils", socialUtils, true);
        FieldUtils.writeField(servlet, "ugcSearch", ugcSearch, true);

        SocialResourceConfiguration src = Mockito.mock(SocialResourceConfiguration.class);
        Mockito.when(src.getAsiPath()).thenReturn(asiPath);
        Mockito.when(socialUtils.getDefaultStorageConfig()).thenReturn(src);
    }

}
