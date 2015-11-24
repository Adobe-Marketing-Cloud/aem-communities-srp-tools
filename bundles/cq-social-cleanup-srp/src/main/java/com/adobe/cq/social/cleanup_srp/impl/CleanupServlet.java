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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.jcr.RepositoryException;
import javax.servlet.ServletException;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestParameter;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.cq.social.srp.SocialResourceProvider;
import com.adobe.cq.social.ugc.api.PathConstraint;
import com.adobe.cq.social.ugc.api.PathConstraintType;
import com.adobe.cq.social.ugc.api.SearchResults;
import com.adobe.cq.social.ugc.api.UgcFilter;
import com.adobe.cq.social.ugc.api.UgcSearch;
import com.adobe.cq.social.ugcbase.SocialUtils;

/**
 * This servlet will hunt down and delete all UGC under a specified path. For example
 * "curl -X POST http://localhost:4502/services/social/srp/cleanup?path=/content/usergenerated/asi/cloud -uadmin:admin"
 * will delete everything under /content/usergenerated/asi/cloud. It will only delete content that the given user has
 * read and delete access to. By default, things will be deleted in batches of approximately 100 items. This can be
 * modified with the optional batchSize parameter (eg, batchSize=200 will do batches of 200).
 */
@Component(specVersion = "1.0")
@Service
@Properties({@Property(name = "sling.servlet.paths", value = "/services/social/srp/cleanup")})
public class CleanupServlet extends SlingAllMethodsServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(CleanupServlet.class);
    private static final int BATCH_SIZE = 100;

    @Reference
    private UgcSearch ugcSearch;

    @Reference
    private SocialUtils socialUtils;

    /**
     * Post method.
     * @param request the request object
     * @param response the response object
     * @throws ServletException if something went wrong
     * @throws IOException if something could not be read or written
     */
    protected void doPost(final SlingHttpServletRequest request, final SlingHttpServletResponse response)
        throws ServletException, IOException {
        RequestParameter param = request.getRequestParameter("path");
        String path = null;
        if (param != null) {
            path = param.getString("UTF-8");
            if (path == null || path.isEmpty()) {
                throw new ServletException("Path is required");
            }
        } else {
            throw new ServletException("Path is required");
        }

        if (!path.startsWith(socialUtils.getDefaultStorageConfig().getAsiPath())) {
            throw new ServletException(
                "Path must begin with a UGC storage location (eg, /content/usergenerated/asi/cloud");
        }

        int batchSize = BATCH_SIZE;
        param = request.getRequestParameter("batchSize");
        if (param != null) {
            String batchString = param.getString("UTF-8");
            if (batchString != null && !batchString.isEmpty()) {
                try {
                    batchSize = Integer.parseInt(batchString);
                } catch (final NumberFormatException e) {
                    throw new ServletException("Batch size could not be parsed", e);
                }
            }
        }

        try {
            cleanup(request.getResourceResolver(), batchSize, path);
        } catch (final RepositoryException e) {
            throw new ServletException("Could not delete content", e);
        }
    }

    private void cleanup(final ResourceResolver resolver, final int batchSize, final String path)
        throws RepositoryException, PersistenceException {
        long total = 0;
        boolean retried = false;
        while (true) {
            SearchResults<Resource> searchResults = getResources(resolver, batchSize, path);
            List<Resource> results = removeChildren(searchResults.getResults());
            if (results.isEmpty()) {
                if (searchResults.getTotalNumberOfResults() > 0 && !retried) {
                    try {
                        // Despite our sleep below, it looks like there is still data around, but we didn't find it.
                        // Give things more time to get consistent and try one more time.
                        LOG.info("Resource list is empty, but it looks like the data store isn't consistent yet. Letting things settle and retrying");

                        Thread.sleep(5000);
                    } catch (final InterruptedException e) {
                        // If awoken, continue
                    }
                    retried = true;
                    continue;
                }
                return;
            }

            retried = false;

            for (Resource resource : results) {
                LOG.debug("Deleting {}", resource.getPath());
                try {
                    // This must be a resource that is owned by a SocialResourceProvider. Force SRP to be used to delete it.
                    SocialResourceProvider socialResourceProvider = socialUtils.getConfiguredProvider(resource);
                    socialResourceProvider.delete(resolver, resource.getPath());
                } catch (final PersistenceException e) {
                    LOG.debug("Resource already deleted {}", resource.getPath());
                }
            }

            resolver.commit();

            total += results.size();
            LOG.info("Deleted {}", total);
            try {
                // 2 reasons for this sleep: 1) don't let this servlet flood the backend..we're in no particular hurry
                // here 2) some of the SRPs are backed by eventually consistent data stores (eg, solr)...if we query
                // right away, we may get back data that we just deleted, which is a waste of cycles..
                Thread.sleep(1000);
            } catch (final InterruptedException e) {
                // If awoken, continue
            }
        }
    }

    private SearchResults<Resource> getResources(final ResourceResolver resolver, final int num, final String path)
        throws RepositoryException {
        UgcFilter filter = new UgcFilter();
        filter.addConstraint(new PathConstraint(path, PathConstraintType.IsDescendantNode));
        SearchResults<Resource> results = ugcSearch.find(null, resolver, filter, 0, num, false);
        return results;
    }

    List<Resource> removeChildren(final List<Resource> resources) {

        // Go through the list of paths to see if the parent is included in the list. If so, we don't also
        // need to delete the child (it will be deleted when the parent is deleted).
        List<Resource> finalList = new ArrayList<Resource>(resources);
        Set<String> paths = new HashSet<String>();
        for (Resource resource : resources) {
            paths.add(resource.getPath());
        }

        for (Resource resource : resources) {
            // this is cheaper than grabbing the parent resource if the parent isn't in the list
            if (resource.getValueMap().containsKey(SocialUtils.PN_PARENTID)
                    && paths.contains(resource.getValueMap().get(SocialUtils.PN_PARENTID))) {
                finalList.remove(resource);
            }

        }

        return finalList;

    }
}
