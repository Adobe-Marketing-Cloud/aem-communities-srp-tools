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
import java.io.PrintWriter;
import java.util.Iterator;
import java.util.List;

import javax.jcr.RepositoryException;
import javax.servlet.ServletException;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestParameter;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.cq.social.commons.CollabUser;
import com.adobe.cq.social.srp.SocialResource;
import com.adobe.cq.social.srp.SocialResourceProvider;
import com.adobe.cq.social.srp.config.SocialResourceConfiguration;
import com.adobe.cq.social.ugc.api.PathConstraint;
import com.adobe.cq.social.ugc.api.PathConstraintType;
import com.adobe.cq.social.ugc.api.SearchResults;
import com.adobe.cq.social.ugc.api.UgcFilter;
import com.adobe.cq.social.ugc.api.UgcSearch;
import com.adobe.cq.social.ugcbase.SocialUtils;
import com.adobe.cq.social.ugcbase.core.SocialResourceUtils;
import com.adobe.granite.security.user.UserProperties;
import com.day.cq.commons.Externalizer;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.day.cq.wcm.commons.WCMUtils;

/**
 * This servlet will hunt down and update missing author info on all forum posts under a specified path. For example
 * "curl -u admin:admin -X POST -F saveChanges=true -F path=/content/usergenerated/asi/cloud http://localhost:4502/services/social/srp/fixauthorinfo"
 * will find all forum posts under/content/usergenerated/asi/cloud. It will only update content that the given user has
 * read and delete access to. If saveChanges is not "true" or not set then it will be a dry run.
 */
@Component
@Service
@Properties({@Property(name = "sling.servlet.paths", value = "/services/social/srp/fixauthorinfo")})
public class FixForumAuthorInfoServlet extends SlingAllMethodsServlet {
    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(FixForumAuthorInfoServlet.class);
    private static final int BATCH_SIZE = 100;
    
    @Reference
    private UgcSearch ugcSearch;

    @Reference
    private SocialUtils socialUtils;
    
    private static volatile boolean isRunning = false;
    private static volatile boolean requestStop = false;
    
    private Thread runningThread;
    
    protected void doGet(final SlingHttpServletRequest request, final SlingHttpServletResponse response) throws ServletException, IOException {
    	doPost(request, response);
    }
    
    /**
     * Post method.
     * @param request the request object
     * @param response the response object
     * @throws ServletException if something went wrong
     * @throws IOException if something could not be read or written
     */
    protected void doPost(final SlingHttpServletRequest request, final SlingHttpServletResponse response)
        throws ServletException, IOException {
    	response.setContentType("text/html;charset=utf-8");
    	
    	//stop the running process after it finishes the current batch
    	if(request.getRequestParameter("cancel") != null) {
    		cancel();
        	response.getWriter().append("Cancel requested");
        	return;
    	}
        
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
            throw new ServletException("Path must begin with a UGC storage location. Was expecting "
                    + socialUtils.getDefaultStorageConfig().getAsiPath() + ". Received " + path);
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
        
        boolean saveChanges = (request.getParameter("saveChanges") != null && request.getParameter("saveChanges").equals("true"));
        if(!saveChanges) response.getWriter().println("Running as dry run, to make changes add request parameter saveChanges=true<br>");
        try {
        	runFixAuthorInfo(request.getResourceResolver(), batchSize, path, saveChanges, response.getWriter());
        } catch (final RepositoryException e) {
            throw new ServletException("Could not update author info", e);
        }
    }
        
    public void runFixAuthorInfo(final ResourceResolver resolver, final int batchSize, final String path, boolean saveChanges, PrintWriter output) throws PersistenceException, RepositoryException {
    	synchronized(this) {
        	if(isRunning) {
            	output.append("Process already running");
            	return;
            } else {
            	isRunning = true;
            	runningThread = Thread.currentThread();
            }
    	}
    	fixAuthorInfo(resolver, batchSize, path, saveChanges, output);
    	synchronized(this) {
    		isRunning = false;
    		runningThread = null;
    	}
    }
    
    public synchronized void cancel() {
    	synchronized(this) {
    		if(runningThread != null) requestStop = true;
    	}
    }
    
    private void fixAuthorInfo(final ResourceResolver resolver, final int batchSize, final String path, boolean saveChanges, PrintWriter output)
        throws RepositoryException, PersistenceException {
        
    	boolean retried = false;
        UserManager userManager = resolver.adaptTo(UserManager.class);
        SocialResourceConfiguration config = socialUtils.getDefaultStorageConfig();
        SocialResource rootResource = (SocialResource) resolver.getResource(config.getAsiPath());
        rootResource.getResourceProvider().setConfig(config);
        
        long totalFixed = 0;
                
        int counter = 0;
        boolean hasMore;
        do {
        	hasMore = false;
        	final int offset = counter * batchSize;
	        SearchResults<Resource> searchResults = getResources(resolver, BATCH_SIZE, offset, path);
	        List<Resource> list = searchResults.getResults();
	        final long totalSize = searchResults.getTotalNumberOfResults();
	        if (totalSize > 0 && totalSize > (counter + 1) * batchSize) {
	            LOG.debug("More results remain to be found");
	            hasMore = true;
	        }
	        
	        Iterator<Resource> iter = list.iterator();
	        while(iter.hasNext()) {
	            Resource res = iter.next();
	            ModifiableValueMap props = res.adaptTo(ModifiableValueMap.class);
	        	try {
	        		if(needsFixing(props)) {
	        			String userId = props.get(CollabUser.PROP_NAME, "").toString();
	        			if("".equals(userId.trim())) userId = props.get("authorizableId", "");
	        			if(!"".equals(userId.trim()) ) {
	        				output.println((totalFixed+1) + ". Updating user: <b>" + userId + "</b>, for post: <b>"+ res.getPath() + "</b><br>");
	        				addSocialSpecificFields(resolver, props, userId, output, saveChanges);
	        			} else {
	        				output.println((totalFixed+1) + ". User info missing for post: <b>"+ res.getPath() + "</b><br>");
	        			}
						totalFixed++;
	        		}
				} catch (Exception e) {
					output.println("No changes made: " + e.getMessage() + " " + ((e.getCause() != null)?e.getCause().getMessage():"") + "<br>");
				}
	        }
	        counter++;
	        if(saveChanges && totalFixed % batchSize == 0) {
	        	output.flush();
	        	output.println("Saving.. <br>");
	        	resolver.commit();
	        }
        	if(requestStop) {
        		synchronized(this) {
        			isRunning = false;
        			runningThread = null;
        			requestStop = false;
        		}
        		output.println("<br>Process cancelled");
        		return;
        	}
	        try {
	        	//Don't overload the backend and allow interruption of thread
	        	Thread.sleep(500);
	        } catch (InterruptedException ie) {
        		synchronized(this) {
        			isRunning = false;
        			runningThread = null;
        			requestStop = false;
        			output.println("<br>Process cancelled");
        		}
	        	return;
	        }
        } while (hasMore);
        if(saveChanges) {
        	output.println("Saving.. <br>");
        	resolver.commit();
        }
        output.println("Done, updated " + totalFixed + " posts");
    }
            
    private void addSocialSpecificFields(final ResourceResolver resolver, final ModifiableValueMap map, String userId, PrintWriter output, boolean saveChanges) throws ServletException {
    	Externalizer externalizer = resolver.adaptTo(Externalizer.class);
        /*if (map.containsKey(SocialUtils.PN_CS_ROOT) && map.containsKey(SocialUtils.PN_PARENTID)) {
            final String parent = (String) map.get(SocialUtils.PN_PARENTID);
            final String root = (String) map.get(SocialUtils.PN_CS_ROOT);
            map.put(SocialUtils.PN_IS_REPLY, !StringUtils.equals(parent, root));
        }*/
        if (userId != null && !"".equals(userId) && map.containsKey(SocialUtils.PN_CS_ROOT)) {
            final UserProperties up =
                SocialResourceUtils.getUserProperties(resolver, (String) map.get(CollabUser.PROP_NAME));
            if (up != null) {
                try {
                    final PageManager pageManager = resolver.adaptTo(PageManager.class);
                    final String displayName = up.getDisplayName();
                    // send size 34px need commons release for that.
                    final String authorAvatar =
                        SocialResourceUtils.getAvatar(up, up.getAuthorizableID(), SocialUtils.AVATAR_SIZE.THIRTY_TWO);
                    final String basePath = (String) map.get(SocialUtils.PN_CS_ROOT);
                    final Page ugcParentPage = pageManager.getContainingPage(basePath);
                    final String socialProfilePage =
                        WCMUtils.getInheritedProperty(ugcParentPage, resolver, "cq:socialProfilePage");
                    final String authorPath = up.getNode().getPath();
                    if(!map.containsKey("userIdentifier") || "".equals(map.get("userIdentifier", ""))) {
                    	if(saveChanges) map.put("userIdentifier", userId);
                    }
                    if(!map.containsKey("authorizableId") || "".equals(map.get("authorizableId", ""))) {
                    	if(saveChanges) map.put("authorizableId", userId);
                    }
                    /*if((!map.containsKey("email") || "".equals(map.get("email", ""))) && up.getProperty("./profile/email") != null) {
                    	if(saveChanges) map.put("email", up.getProperty("./profile/email"));
                        output.print("  * email=" + up.getProperty("./profile/email"));
                    }*/
                    if(saveChanges) map.put("author_display_name", displayName);
                    output.print("  * author_display_name=" + displayName);
                    output.println("<br>");

                    /*if (externalizer != null) {
                    	if(saveChanges) {
                    		map.put("author_image_url",
                            externalizer.publishLink(resolver, authorAvatar));
                    		map.put("author_profile_url",
                    				externalizer.publishLink(resolver, authorPath + ".form.html" + ((socialProfilePage != null)?socialProfilePage:"")));
                    	}
                    	output.println("  * author_image_url=" + externalizer.publishLink(resolver, authorAvatar) + "<br>");
                    	output.print("  * author_profile_url=" +
                                externalizer.publishLink(resolver, authorPath + ".form.html" + ((socialProfilePage != null)?socialProfilePage:"")));
                        output.println("<br>");
                    }*/
                } catch (final RepositoryException e) {
                    throw new ServletException("Could not get display name!", e);
                }
            } else {
            	throw new ServletException("Could not get user properties.");
            }
        }
    }


    private SearchResults<Resource> getResources(final ResourceResolver resolver, final int batchSize, final int offset, final String path)
        throws RepositoryException {
        UgcFilter filter = new UgcFilter();
        filter.addConstraint(new PathConstraint(path, PathConstraintType.IsDescendantNode));
        SearchResults<Resource> results = ugcSearch.find(null, resolver, filter, offset, batchSize, true);
        return results;
    }

	private boolean needsFixing(ValueMap props) {
		return (props.get("social:baseType", "").equals("social/commons/components/comments/comment")
				&& (props.containsKey(CollabUser.PROP_NAME) || props.containsKey("authorizableId"))
				&& props.containsKey(SocialUtils.PN_CS_ROOT) && !props.containsKey("author_display_name"));
		}
    
}
