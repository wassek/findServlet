package org.jahia.modules.findservlet.servlet;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.query.InvalidQueryException;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;
import javax.jcr.query.Row;
import javax.jcr.query.RowIterator;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.StringUtils;
import org.apache.lucene.queryParser.QueryParser;
import org.jahia.api.Constants;
import org.jahia.exceptions.JahiaForbiddenAccessException;
import org.jahia.services.SpringContextSingleton;
import org.jahia.services.content.JCRContentUtils;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.render.RenderException;
import org.jahia.services.render.URLResolver;
import org.jahia.services.render.URLResolverFactory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FindServlet extends HttpServlet {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	
	
	
      private int defaultLimit = 20;
      
      private int hardLimit = 100;
	   
	   private static Logger logger = LoggerFactory.getLogger(FindServlet.class);

	    private int defaultDepthLimit = 1;

	    private boolean defaultEscapeColon = false;

	    private boolean defaultRemoveDuplicatePropertyValues = false;

	    private URLResolverFactory urlResolverFactory;

	    private Set<String> nodeTypesToSkip;
	    
	    
	    private Set<String> propertiesToSkip;

	    private Map<String, Set<String>> propertiesToSkipByNodeType;
	    
	    
		   @Override
		    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		        doPost(req, resp);
		    }
		   
		   @Override
		    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
			   try {
			        handle(req, resp);
			   } catch (Exception ex) {
				   throw new ServletException("Error in FindServlet", ex);
			   }
		    }
		   	    

	    private static Set<String> toSet(String source) {
	        return source != null && source.length() > 0 ? new LinkedHashSet<String>(Arrays.asList(StringUtils.split(
	                source, ", "))) : null;
	    }
	    
	    public void setUrlResolverFactory(URLResolverFactory urlResolverFactory) {
	        this.urlResolverFactory = urlResolverFactory;
	    }

	    private int getInt(String paramName, int defaultValue, HttpServletRequest req) throws IllegalArgumentException {
	        int param = defaultValue;
	        String valueStr = req.getParameter(paramName);
	        if (StringUtils.isNotEmpty(valueStr)) {
	            try {
	                param = Integer.parseInt(valueStr);
	            } catch (NumberFormatException nfe) {
	                throw new IllegalArgumentException("Invalid integer value '" + valueStr + "' for request parameter '"
	                        + paramName + "'", nfe);
	            }
	        }

	        return param;
	    }

	    private Query getQuery(HttpServletRequest request, HttpServletResponse response, String workspace, Locale locale)
	            throws IOException, RepositoryException {
            if (workspace == null) {
            	workspace = "live";
            }
	        QueryManager qm = JCRSessionFactory.getInstance().getCurrentUserSession(workspace, locale).getWorkspace()
	                .getQueryManager();
	        if (qm == null) {
	            response.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED);
	            return null;
	        }

	        String query = request.getParameter("query");
	        if (StringUtils.isEmpty(query)) {
	            response.sendError(HttpServletResponse.SC_BAD_REQUEST,
	                    "Mandatory parameter 'query' is not found in the request");
	            return null;
	        }

	        // now let's parse the query to see if it references any other request parameters, and replace the reference with
	        // the actual value.

	        query = expandRequestMarkers(request, query, true, StringUtils.defaultIfEmpty(request.getParameter("language"), Query.JCR_SQL2), false);
	        logger.debug("Using expanded query=[{}]", query);

	        Query q = qm.createQuery(query, StringUtils.defaultIfEmpty(request.getParameter("language"), Query.JCR_SQL2));

	        int limit = getInt("limit", defaultLimit, request);
	        if (limit <= 0 || limit > hardLimit) {
	            limit = hardLimit;
	        }
	        
	        int offset = getInt("offset", 0, request);

	        if (limit > 0) {
	            q.setLimit(limit);
	        }
	        if (offset > 0) {
	            q.setOffset(offset);
	        }

	        return q;
	    }

	    protected String expandRequestMarkers(HttpServletRequest request, String sourceString, boolean escapeValue, String queryLanguage, boolean escapeForRegexp) {
	        String result = new String(sourceString);
	        int refMarkerPos = result.indexOf("{$");
	        while (refMarkerPos >= 0) {
	            int endRefMarkerPos = result.indexOf("}", refMarkerPos);
	            if (endRefMarkerPos > 0) {
	                String refName = result.substring(refMarkerPos + 2, endRefMarkerPos);
	                String refValue = request.getParameter(refName);
	                if (refValue != null) {
	                     // now it's very important that we escape it properly to avoid injection security holes
	                    if (escapeValue) {
	                        refValue = QueryParser.escape(refValue);
	                        if (Query.XPATH.equals(queryLanguage)) {
	                            // found this here : http://markmail.org/thread/pd7myawyv2dadmdh
	                            refValue = StringUtils.replace(refValue,"'", "\\'");
	                        } else {
	                        }
	                        refValue = StringUtils.replace(refValue, "'", "''");
	                    }
	                    if (escapeForRegexp) {
	                        refValue = Pattern.quote(refValue);
	                    }
	                     result = StringUtils.replace(result, "{$" + refName + "}", refValue);
	                } else {
	                    // the request parameter wasn't found, so we leave the marker as it is, simply ignoring it.
	                }
	            }
	            refMarkerPos = result.indexOf("{$", refMarkerPos + 2);
	        }
	        return result;
	    }


	    protected void handle(HttpServletRequest request, HttpServletResponse response) throws RenderException,
	            IOException, RepositoryException, JahiaForbiddenAccessException {
	        
	        /*checkUserLoggedIn();
	        checkUserAuthorized();*/
	        if (urlResolverFactory == null) {
	        	urlResolverFactory = (URLResolverFactory) SpringContextSingleton.getBean("urlResolverFactory");
	        }
	        URLResolver urlResolver = urlResolverFactory.createURLResolver(request.getPathInfo(), request.getServerName(), request);
	        try {
	            Query query = getQuery(request, response, (StringUtils.isEmpty(urlResolver.getWorkspace()))? "live":urlResolver.getWorkspace(), urlResolver.getLocale());
	            if (query == null) {
	                return;
	            }
	            if (logger.isDebugEnabled()) {
	                logger.debug("Executing " + query.getLanguage() + " for workspace '" + urlResolver.getWorkspace() + "' and locale '"
	                        + urlResolver.getLocale() + "'. Statement: " + query.getStatement());
	            }
	            writeResults(query.execute(), request, response, query.getLanguage());
	        } catch (IllegalArgumentException e) {
	            logger.error("Invalid argument", e);
	            response.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
	        } catch (InvalidQueryException e) {
	            logger.error("Invalid query", e);
	            response.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
	        }
	    }

	    private JSONObject serializeNode(Node currentNode, int depthLimit, boolean escapeColon, Pattern propertyMatchRegexp, Map<String, String> alreadyIncludedPropertyValues) throws RepositoryException,
	            JSONException {
	        if (skipNode(currentNode)) {
	            return null;
	        }
	        final PropertyIterator stringMap = currentNode.getProperties();
	        JSONObject jsonObject = new JSONObject();
	        // Map<String,Object> map = new HashMap<String, Object>();
	        Set<String> matchingProperties = new HashSet<String>();
	        while (stringMap.hasNext()) {
	            JCRPropertyWrapper propertyWrapper = (JCRPropertyWrapper) stringMap.next();
	            if (skipProperty(propertyWrapper)) {
	                continue;
	            }
	            final int type = propertyWrapper.getType();
	            final String name = escapeColon ? JCRContentUtils.replaceColon(propertyWrapper.getName()) : propertyWrapper.getName();
	            if (type == PropertyType.BINARY) {
	                continue;
	            }
	            if (type == PropertyType.WEAKREFERENCE || type == PropertyType.REFERENCE) {
	                if (!propertyWrapper.isMultiple()) {
	                  try{
	                    jsonObject.put(name, ((JCRNodeWrapper) propertyWrapper.getNode()).getUrl());
	                  }catch(ItemNotFoundException ex) {
	                	  logger.warn("Referenced Item cannot be found (To solve it you can run the JCR Integrity Tools):", ex);
	                	  jsonObject.put(name, propertyWrapper.getValue().getString());
	                  }
	                }
	            } else {
	                if (!propertyWrapper.isMultiple()) {
	                    jsonObject.put(name, propertyWrapper.getValue().getString());
	                    // @todo this code is duplicated for multiple values, we need to clean this up.
	                    if (propertyMatchRegexp != null && propertyMatchRegexp.matcher(propertyWrapper.getValue().getString()).matches()) {
	                        if (alreadyIncludedPropertyValues != null) {
	                            String nodeIdentifier = alreadyIncludedPropertyValues.get(propertyWrapper.getValue().getString());
	                            if (nodeIdentifier != null) {
	                                if (!nodeIdentifier.equals(currentNode.getIdentifier())) {
	                                    // This property value already exists and comes from another node.
	                                    return null;
	                                }
	                            } else {
	                                alreadyIncludedPropertyValues.put(propertyWrapper.getValue().getString(), currentNode.getIdentifier());
	                            }
	                        }
	                        // property starts with the propertyMatchRegexp, let's add it to the list of matching properties.
	                        matchingProperties.add(name);
	                    }
	                } else {
	                    JSONArray jsonArray = new JSONArray();
	                    Value[] propValues = propertyWrapper.getValues();
	                    for (Value propValue : propValues) {
	                        jsonArray.put(propValue.getString());
	                        if (propertyMatchRegexp != null && propertyMatchRegexp.matcher(propValue.getString()).matches()) {
	                            if (alreadyIncludedPropertyValues != null) {
	                                String nodeIdentifier = alreadyIncludedPropertyValues.get(propValue.getString());
	                                if (nodeIdentifier != null) {
	                                    if (!nodeIdentifier.equals(currentNode.getIdentifier())) {
	                                        // This property value already exists and comes from another node.
	                                        return null;
	                                    }
	                                } else {
	                                    alreadyIncludedPropertyValues.put(propValue.getString(), currentNode.getIdentifier());
	                                }
	                            }
	                            // property starts with the propertyMatchRegexp, let's add it to the list of matching properties.
	                            matchingProperties.add(name);
	                        }
	                    }
	                    jsonObject.put(name, jsonArray);
	                }
	            }
	        }
	        // now let's output some node information.
	        jsonObject.put("path", currentNode.getPath());
	        jsonObject.put("identifier", currentNode.getIdentifier());
	        jsonObject.put("index", currentNode.getIndex());
	        jsonObject.put("depth", currentNode.getDepth());
	        jsonObject.put("nodename", currentNode.getName());
	        jsonObject.put("primaryNodeType", currentNode.getPrimaryNodeType().getName());
	        if (propertyMatchRegexp != null) {
	            jsonObject.put("matchingProperties", new JSONArray(matchingProperties));
	        }

	        // now let's output the children until we reach the depth limit.
	        if ((depthLimit - 1) > 0) {
	            final NodeIterator childNodeIterator = currentNode.getNodes();
	            JSONArray childMapList = new JSONArray();
	            while (childNodeIterator.hasNext()) {
	                Node currentChildNode = childNodeIterator.nextNode();
	                JSONObject childSerializedMap = serializeNode(currentChildNode, depthLimit - 1, escapeColon, propertyMatchRegexp, alreadyIncludedPropertyValues);
	                if (childSerializedMap != null) {
	                    childMapList.put(childSerializedMap);
	                }
	            }
	            jsonObject.put("childNodes", childMapList);
	        }
	        return jsonObject;
	    }

	    private JSONObject serializeRow(Row row, String[] columns, int depthLimit, boolean escapeColon, Set<String> alreadyIncludedIdentifiers, Pattern propertyMatchRegexp, Map<String, String> alreadyIncludedPropertyValues) throws RepositoryException,
	            JSONException {
	        Node currentNode = row.getNode();

	        if (currentNode != null && skipNode(currentNode)) {
	            return null;
	        }

	        JSONObject jsonObject = new JSONObject();

	        if (currentNode != null) {
	            if (currentNode.isNodeType(Constants.JAHIANT_TRANSLATION)) {
	                try {
	                    currentNode = currentNode.getParent();
	                    if (alreadyIncludedIdentifiers.contains(currentNode.getIdentifier())) {
	                        // avoid duplicates due to j:translation nodes.
	                        return null;
	                    }
	                    JSONObject serializedNode = serializeNode(currentNode, depthLimit, escapeColon, propertyMatchRegexp, alreadyIncludedPropertyValues);
	                    if (serializedNode == null) {
	                        return null;
	                    }
	                    jsonObject.put("node", serializedNode);
	                    alreadyIncludedIdentifiers.add(currentNode.getIdentifier());
	                } catch (ItemNotFoundException e) {
	                    currentNode = null;
	                }
	            } else {
	                if (alreadyIncludedIdentifiers.contains(currentNode.getIdentifier())) {
	                    // avoid duplicates due to j:translation nodes.
	                    return null;
	                }
	                JSONObject serializedNode = serializeNode(currentNode, depthLimit, escapeColon, propertyMatchRegexp, alreadyIncludedPropertyValues);
	                if (serializedNode == null) { 
	                    return null;
	                }
	                jsonObject.put("node", serializedNode);
	                alreadyIncludedIdentifiers.add(currentNode.getIdentifier());
	            }

	        }

	        for (String column : columns) {
	            if (currentNode != null) {
	                if (!"jcr:score".equals(column)
	                        && !"jcr:path".equals(column)
	                        && !column.startsWith("rep:")
	                        && !currentNode.hasProperty(column.contains(".") ? StringUtils.substringAfter(column, ".")
	                                : column)) {
	                    continue;
	                }
	            }
	            try {
	                if (skipRowColumn(column)) {
	                    continue;
	                }
	                Value value = row.getValue(column);
	                jsonObject.put(escapeColon ? JCRContentUtils.replaceColon(column) : column, value != null ? value.getString() : null);
	            } catch (ItemNotFoundException infe) {
	                logger.warn("No value found for column " + column);
	            } catch (PathNotFoundException pnfe) {
	                logger.warn("No value found for column " + column);                
	            }
	        }


	        return jsonObject;
	    }

	    /**
	     * @param defaultDepthLimit the defaultDepthLimit to set
	     */
	    public void setDefaultDepthLimit(int defaultDepthLimit) {
	        this.defaultDepthLimit = defaultDepthLimit;
	    }

	    /**
	     * @param defaultEscapeColon the defaultEscapeColon to set
	     */
	    public void setDefaultEscapeColon(boolean defaultEscapeColon) {
	        this.defaultEscapeColon = defaultEscapeColon;
	    }

	    public boolean isDefaultRemoveDuplicatePropertyValues() {
	        return defaultRemoveDuplicatePropertyValues;
	    }

	    public void setDefaultRemoveDuplicatePropertyValues(boolean defaultRemoveDuplicatePropertyValues) {
	        this.defaultRemoveDuplicatePropertyValues = defaultRemoveDuplicatePropertyValues;
	    }

	    private void writeResults(QueryResult result, HttpServletRequest request, HttpServletResponse response, String queryLanguage)
	            throws RepositoryException, IllegalArgumentException, IOException, RenderException {
	        response.setContentType("application/json; charset=UTF-8");
	        int depth = getInt("depthLimit", defaultDepthLimit, request);
	        boolean escape = Boolean.valueOf(StringUtils.defaultIfEmpty(request.getParameter("escapeColon"), String
	                .valueOf(defaultEscapeColon)));
	        boolean removeDuplicatePropertyValues = Boolean.valueOf(StringUtils.defaultIfEmpty(request.getParameter("removeDuplicatePropValues"), String
	                .valueOf(defaultRemoveDuplicatePropertyValues)));

	        Pattern propertyMatchRegexp = null;
	        String propertyMatchRegexpString = request.getParameter("propertyMatchRegexp");
	        if (propertyMatchRegexpString != null) {
	            String expandedPattern = expandRequestMarkers(request, propertyMatchRegexpString, false, queryLanguage, true);
	            propertyMatchRegexp = Pattern.compile(expandedPattern, Pattern.CASE_INSENSITIVE);
	        }

	        JSONArray results = new JSONArray();
	        
	        try {
	            String[] columns = result.getColumnNames();
	            boolean serializeRows = !Boolean.parseBoolean(request.getParameter("getNodes")) && columns.length > 0 && !columns[0].contains("*");

	            Set<String> alreadyIncludedIdentifiers = new HashSet<String>();
	            Map<String, String> alreadyIncludedPropertyValues = null;
	            if (removeDuplicatePropertyValues) {
	                alreadyIncludedPropertyValues = new HashMap<String, String>();
	            }
	            int resultCount = 0;
	            if (serializeRows) {
	                logger.debug("Serializing rows into JSON result structure...");
	                RowIterator rows = result.getRows();
	                while (rows.hasNext()) {
	                    Row row = rows.nextRow();
	                    JSONObject serializedRow = serializeRow(row, columns, depth, escape, alreadyIncludedIdentifiers, propertyMatchRegexp, alreadyIncludedPropertyValues);
	                    if (serializedRow != null) {
	                        results.put(serializedRow);
	                        resultCount++;
	                    }
	                }
	            } else {
	                logger.debug("Serializing nodes into JSON result structure...");
	                NodeIterator nodes = result.getNodes();
	                while (nodes.hasNext()) {
	                    Node nextNode = nodes.nextNode();
	                    JSONObject serializedNode = serializeNode(nextNode, depth, escape, propertyMatchRegexp, alreadyIncludedPropertyValues);
	                    if (serializedNode != null) {
	                        results.put(serializedNode);
	                        resultCount++;
	                    }
	                }
	            }
	            logger.debug("Found {} results.", resultCount);
	            results.write(response.getWriter());
	        } catch (JSONException e) {
	            throw new RenderException(e);
	        }
	    }





	    public void setPropertiesToSkip(String propertiesToSkipString) {
	        propertiesToSkip = toSet(propertiesToSkipString);
	        if (propertiesToSkip == null) {
	            propertiesToSkipByNodeType = null;
	        } else {
	            propertiesToSkipByNodeType = new HashMap<String, Set<String>>();
	            for (String p : propertiesToSkip) {
	                String ntName = "*";
	                String propName = p;
	                int pos = p.indexOf('.');
	                if (pos != -1) {
	                    ntName = p.substring(0, pos);
	                    propName = p.substring(pos + 1, p.length());
	                } else {
	                    propertiesToSkip.add("*." + p);
	                }
	                Set<String> ntProps = propertiesToSkipByNodeType.get(ntName);
	                if (ntProps == null) {
	                    ntProps = new HashSet<String>();
	                    propertiesToSkipByNodeType.put(ntName, ntProps);
	                }
	                ntProps.add(propName);
	            }
	        }
	    }

	    private boolean skipNode(Node currentNode) throws RepositoryException {

	        if (nodeTypesToSkip == null) {
	        	nodeTypesToSkip = new HashSet<String>();
	        	nodeTypesToSkip.add("jnt:passwordHistory");
	        	nodeTypesToSkip.add("jnt:passwordHistoryEntry");
	        }

	            String primary = currentNode.getPrimaryNodeType().getName();
	            if (nodeTypesToSkip.contains(primary)) {
	                return true;
	            }
	            for (String nt : nodeTypesToSkip) {
	                if (currentNode.isNodeType(nt)) {
	                    return true;
	                }
	            }
	        return false;
	    }

	    private boolean skipProperty(JCRPropertyWrapper property) throws RepositoryException {
	        if (propertiesToSkipByNodeType == null) {
	        	
	        	propertiesToSkipByNodeType = new HashMap<String, Set<String>>();
	        	Set<String> propSkip1 = new HashSet<String>();
	        	propSkip1.add("j:password");
	        	propertiesToSkipByNodeType.put("jnt:user", propSkip1);
	        	propertiesToSkipByNodeType.put("jnt:passwordHistoryEntry", propSkip1);
	        }
	            String nt = property.getDefinition().getDeclaringNodeType().getName();
	            Set<String> props = propertiesToSkipByNodeType.get(nt);
	            String propName = property.getName();
	            boolean skip = props != null && props != null && props.contains(propName);
	            if (!skip) {
	                skip = props != null && props.contains(propName);
	            }
	            return skip;

	    }

	    private boolean skipRowColumn(String column) throws RepositoryException {
	        return propertiesToSkip != null && propertiesToSkip.contains(column);
	    }

}
