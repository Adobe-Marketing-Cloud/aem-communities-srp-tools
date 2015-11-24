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

import com.adobe.cq.social.srp.SocialResourceProvider;
import com.adobe.cq.social.srp.config.SocialResourceConfiguration;
import com.adobe.cq.social.ugc.api.SearchResults;
import com.adobe.cq.social.ugc.api.UgcFilter;
import com.adobe.cq.social.ugc.api.UgcSearch;
import com.adobe.cq.social.ugcbase.SocialUtils;

public class TestCleanupServlet {
    private CleanupServlet servlet = new CleanupServlet();
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

    /**
     * Test cleanup in the simplest case.
     * @throws RepositoryException on failure
     * @throws IOException on failure
     * @throws ServletException on failure
     */
    @Test
    public void testCleanup() throws RepositoryException, ServletException, IOException {
        // Set up 1 resource that we will delete
        List<Resource> resultList = new ArrayList<Resource>();
        Resource resource = Mockito.mock(Resource.class);
        resultList.add(resource);
        Mockito.when(resource.getPath()).thenReturn(asiPath + "/mypath");
        ValueMap vm = Mockito.mock(ValueMap.class);
        Mockito.when(resource.getValueMap()).thenReturn(vm);
        ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        Mockito.when(resource.getResourceResolver()).thenReturn(resolver);
 
        SocialResourceProvider srp = Mockito.mock(SocialResourceProvider.class);
        Mockito.when(socialUtils.getConfiguredProvider(resource)).thenReturn(srp);
 

        // Add it to the search results.
        SearchResults<Resource> searchResults1 = Mockito.mock(SearchResults.class);
        Mockito.when(searchResults1.getResults()).thenReturn(resultList);
        SearchResults<Resource> searchResults2 = Mockito.mock(SearchResults.class);
        Mockito.when(searchResults2.getResults()).thenReturn(Collections.<Resource>emptyList());

        // The first time we search, return the resource. The second time, return an empty list.
        Mockito
            .when(
                ugcSearch.find(Mockito.anyString(), Mockito.any(ResourceResolver.class),
                    Mockito.any(UgcFilter.class), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyBoolean()))
            .thenReturn(searchResults1).thenReturn(searchResults2);

        // Mock the request
        SlingHttpServletRequest request = Mockito.mock(SlingHttpServletRequest.class);
        Mockito.when(request.getResource()).thenReturn(resource);
        Mockito.when(request.getResourceResolver()).thenReturn(resolver);
        RequestParameter param = Mockito.mock(RequestParameter.class);
        Mockito.when(param.getString("UTF-8")).thenReturn(asiPath);
        Mockito.when(request.getRequestParameter("path")).thenReturn(param);
        SlingHttpServletResponse response = Mockito.mock(SlingHttpServletResponse.class);
        servlet.doPost(request, response);
        Mockito.verify(srp, Mockito.times(1)).delete(resolver, resource.getPath());
        Mockito.verify(resolver, Mockito.times(1)).commit();
    }
}
