package org.georchestra.extractorapp.ws.extractor;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.Query;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.wfs.WFSDataStoreFactory;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.factory.GeoTools;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.geotools.util.NullProgressListener;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.FeatureType;
import org.opengis.feature.type.GeometryDescriptor;
import org.opengis.feature.type.PropertyDescriptor;
import org.opengis.filter.FilterFactory2;
import org.opengis.filter.expression.Literal;
import org.opengis.filter.expression.PropertyName;
import org.opengis.filter.spatial.Intersects;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.TransformException;
import org.opengis.util.ProgressListener;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryCollection;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.LinearRing;
import com.vividsolutions.jts.geom.MultiLineString;
import com.vividsolutions.jts.geom.MultiPoint;
import com.vividsolutions.jts.geom.MultiPolygon;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.geom.Polygon;

/**
 * Obtains data from a WFS and write the data out to the filesystem
 *
 * @author jeichar
 */
public class WfsExtractor {

	protected static final Log LOG = LogFactory.getLog(WcsExtractor.class.getPackage().getName());

    /**
     * Enumerate general types of geometries we accept. Multi/normal is ignored
     * because shapefiles are always multigeom
     *
     * The binding is the class to use when creating shapefile datastores
     *
     * @author jeichar
     */
    enum GeomType {
        POINT (MultiPoint.class), LINE (MultiLineString.class), POLYGON (MultiPolygon.class), GEOMETRY (null);

        public final Class<?> binding;

        private GeomType (Class<?> binding) {
            this.binding = binding;
        }

        /**
         * Find the matching type from the geometry class
         */
        public static GeomType lookup (Class<?> binding) {
            GeomType result;
            if (Polygon.class.isAssignableFrom (binding) || MultiPolygon.class.isAssignableFrom (binding)) {
                result = POLYGON;
            } else if (LineString.class.isAssignableFrom (binding) || LinearRing.class.isAssignableFrom (binding)
                    || MultiLineString.class.isAssignableFrom (binding)) {
                result = LINE;
            } else if (Point.class.isAssignableFrom (binding) || MultiPoint.class.isAssignableFrom (binding)) {
                result = POINT;
            } else if (Geometry.class.isAssignableFrom (binding) || GeometryCollection.class.isAssignableFrom (binding)) {
                result = GEOMETRY;
            } else {
                throw new IllegalArgumentException (binding + " is not a recognized geometry type");
            }

            return result;
        }
    }

    private final File          _basedir;
    private final String _adminUsername;
    private final String _adminPassword;
    private final String _secureHost;

    /**
     *
     * Should only be used by tests
     *
     */
    public WfsExtractor (File basedir) {
        this(basedir, "", "", "localhost");
    }

    /**
     *
     * @param basedir
     *            the directory that the extracted files will be written in
     * @param adminUsername username that give admin access to geoserver
     * @param adminPassword password the the admin user
     * @param secureHost
     */
    public WfsExtractor (File basedir, String adminUsername, String adminPassword, String secureHost) {
        this._basedir = basedir;
        this._adminPassword = adminPassword;
        this._adminUsername = adminUsername;
        this._secureHost = secureHost;
    }

    public void checkPermission(ExtractorLayerRequest request, String secureHost, String username, String roles) throws IOException {
        URL capabilitiesURL = request.capabilitiesURL("WFS", "1.0.0");

        final HttpClientBuilder httpClientBuilder = HttpClientBuilder.create();

        HttpClientContext localContext = HttpClientContext.create();
        final HttpHost httpHost = new HttpHost(capabilitiesURL.getHost(), capabilitiesURL.getPort(), capabilitiesURL.getProtocol());

    	HttpGet get = new HttpGet(capabilitiesURL.toExternalForm());
        if(username != null && (secureHost.equalsIgnoreCase(request._url.getHost())
                || "127.0.0.1".equalsIgnoreCase(request._url.getHost())
                || "localhost".equalsIgnoreCase(request._url.getHost()))) {
        	LOG.debug("WfsExtractor.checkPermission - Secured Server: adding username header and role headers to request for checkPermission");

            addImpersonateUserHeaders(username, roles, get);

            enablePreemptiveBasicAuth(capabilitiesURL, httpClientBuilder, localContext, httpHost, _adminUsername, _adminPassword);
        } else {
        	LOG.debug("WfsExtractor.checkPermission - Non Secured Server");
        }

        final CloseableHttpClient httpclient = httpClientBuilder.build();
        String capabilities = FileUtils.asString(httpclient.execute(httpHost, get, localContext).getEntity().getContent());
        Pattern regex = Pattern.compile("(?m)<FeatureType[^>]*>(\\\\n|\\s)*<Name>\\s*(\\w*:)?"+Pattern.quote(request._layerName)+"\\s*</Name>");
        boolean permitted = regex.matcher(capabilities).find();

        if(!permitted) {
            throw new SecurityException("User does not have sufficient privileges to access the Layer: "+request._layerName+". \n\nCapabilties:  "+capabilities);
        }
    }

    public static void addImpersonateUserHeaders(String username, String roles, HttpGet get) {
        get.addHeader("imp-username", username);
        if(roles != null) get.addHeader("imp-roles", roles);
    }

    public static void enablePreemptiveBasicAuth(URL capabilitiesURL, HttpClientBuilder httpClientBuilder, HttpClientContext localContext,
                                                 HttpHost httpHost, String adminUsername, String adminPassword) {
        CredentialsProvider credsProvider = new BasicCredentialsProvider();
        credsProvider.setCredentials(
                new AuthScope(capabilitiesURL.getHost(), capabilitiesURL.getPort()),
                new UsernamePasswordCredentials(adminUsername, adminPassword));
        httpClientBuilder.setDefaultCredentialsProvider(credsProvider);

        AuthCache authCache = new BasicAuthCache();
        // Generate BASIC scheme object and add it to the local
        // auth cache
        BasicScheme basicAuth = new BasicScheme();
        authCache.put(httpHost, basicAuth);

        // Add AuthCache to the execution context
        localContext.setAuthCache(authCache);
    }

    /**
     * Extract the data as defined in the request object.
     *
     * @return the directory that contains the extracted file
     */
    public File extract (ExtractorLayerRequest request) throws IOException, TransformException, FactoryException {
        if (request._owsType != OWSType.WFS) {
            throw new IllegalArgumentException (request._owsType + "must be WFS for the WfsExtractor");
        }

        Map<String, Serializable> params = new HashMap<String, Serializable> ();
        params.put (WFSDataStoreFactory.URL.key, request.capabilitiesURL ("WFS","1.0.0"));
        params.put (WFSDataStoreFactory.LENIENT.key, true);
        params.put (WFSDataStoreFactory.PROTOCOL.key, true);
        params.put (WFSDataStoreFactory.TIMEOUT.key, Integer.valueOf(60000));
        params.put (WFSDataStoreFactory.MAXFEATURES.key, Integer.valueOf(0));

        // HACK  I want unrestricted access to layers.
        // Security check takes place in ExtractorThread
        if(_secureHost.equalsIgnoreCase(request._url.getHost())
                || "127.0.0.1".equalsIgnoreCase(request._url.getHost())
                || "localhost".equalsIgnoreCase(request._url.getHost())) {
        	LOG.debug("WfsExtractor.extract - Secured Server: Adding extractionUserName to connection params");
            if (_adminUsername != null) params.put(WFSDataStoreFactory.USERNAME.key, _adminUsername);
            if (_adminPassword != null) params.put(WFSDataStoreFactory.PASSWORD.key, _adminPassword);
        } else {
        	LOG.debug("WfsExtractor.extract - Non Secured Server");
        }

        DataStore sourceDs = DataStoreFinder.getDataStore(params);
        SimpleFeatureType sourceSchema = sourceDs.getSchema (request.getWFSName());
        Query query = createQuery(request, sourceSchema);
		SimpleFeatureCollection features = sourceDs.getFeatureSource(request.getWFSName()).getFeatures(query);

        ProgressListener progressListener = new NullProgressListener () {
            @Override
            public void exceptionOccurred (Throwable exception) {
                throw new RuntimeException (exception);
            }
        };
        File basedir = request.createContainingDir(_basedir);

        basedir.mkdirs();

        FeatureWriterStrategy featuresWriter;
        BBoxWriter bboxWriter;
        LOG.debug("Number of features returned : " + features.size());
        if ("shp".equalsIgnoreCase(request._format)) {
            featuresWriter = new ShpFeatureWriter(progressListener, sourceSchema, basedir, features);
        	bboxWriter = new BBoxWriter(request._bbox, basedir, OGRFeatureWriter.FileFormat.shp, request._projection, progressListener );
        } else if ("mif".equalsIgnoreCase(request._format)) {
        	//featuresWriter = new MifFeatureWriter(progressListener, sourceSchema, basedir, features);
        	featuresWriter = new OGRFeatureWriter(progressListener, sourceSchema,  basedir, OGRFeatureWriter.FileFormat.mif, features);
        	bboxWriter = new BBoxWriter(request._bbox, basedir, OGRFeatureWriter.FileFormat.mif, request._projection, progressListener );
        } else if ("tab".equalsIgnoreCase(request._format)) {
        	featuresWriter = new OGRFeatureWriter(progressListener, sourceSchema,  basedir, OGRFeatureWriter.FileFormat.tab, features);
        	bboxWriter = new BBoxWriter(request._bbox, basedir, OGRFeatureWriter.FileFormat.tab, request._projection, progressListener );
        } else if ("kml".equalsIgnoreCase(request._format)) {
        	featuresWriter = new OGRFeatureWriter(progressListener, sourceSchema, basedir, OGRFeatureWriter.FileFormat.kml, features);
        	bboxWriter = new BBoxWriter(request._bbox, basedir, OGRFeatureWriter.FileFormat.kml, request._projection, progressListener );
        } else {
            throw new IllegalArgumentException(request._format + " is not a recognized vector format");
        }
        //generates the feature files and bbox file
        featuresWriter.generateFiles();

        bboxWriter.generateFiles();

        return basedir;
    }

	/* This method is default for testing purposes */
    Query createQuery (ExtractorLayerRequest request, FeatureType schema) throws IOException, TransformException,
            FactoryException {
        switch (request._owsType) {
        case WFS:

            // bbox may not be in the same projection as the data so it sometimes necessary to reproject the request BBOX
            ReferencedEnvelope bbox = request._bbox;
            if (schema.getCoordinateReferenceSystem () != null) {
                bbox = request._bbox.transform (schema.getCoordinateReferenceSystem (), true, 10);
            }

            FilterFactory2 filterFactory = CommonFactoryFinder.getFilterFactory2 (GeoTools.getDefaultHints ());
            String propertyName = schema.getGeometryDescriptor ().getLocalName ();
            PropertyName geomProperty = filterFactory.property (propertyName);
            Geometry bboxGeom = new GeometryFactory ().toGeometry (bbox);
            String epsgCode = "EPSG:"+CRS.lookupEpsgCode(bbox.getCoordinateReferenceSystem(),false);
            bboxGeom.setUserData(epsgCode);

            Literal geometry = filterFactory.literal (bboxGeom);
            Intersects filter = filterFactory.intersects (geomProperty, geometry);

            List<String> properties = new ArrayList<String> ();
            for (PropertyDescriptor desc : schema.getDescriptors ()) {
                if (desc instanceof GeometryDescriptor && desc != schema.getGeometryDescriptor ()) {
                    // shapefiles can only have one geometry so skip any
                    // geometry descriptor that is not the default
                    continue;
                } else {
                    properties.add (desc.getName ().getLocalPart ());
                }
            }

            String[] propArray = properties.toArray (new String[properties.size ()]);
            Query query = new Query (request.getWFSName(), filter, propArray);

            query.setCoordinateSystemReproject (request._projection);

            return query;
        default:
            return null;
        }
    }
}
