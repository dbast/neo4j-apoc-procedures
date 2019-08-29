package apoc.util;

import apoc.ApocConfig;
import apoc.export.util.CountingInputStream;
import apoc.export.util.CountingReader;
import apoc.util.hdfs.HDFSUtils;
import apoc.util.s3.S3URLConnection;
import org.apache.commons.io.output.WriterOutputStream;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static apoc.ApocConfig.APOC_IMPORT_FILE_ALLOW__READ__FROM__FILESYSTEM;
import static apoc.ApocConfig.apocConfig;

/**
 * @author mh
 * @since 22.05.16
 */
public class FileUtils {

    public static final String S3_PROTOCOL = "s3";
    public static final boolean S3_ENABLED = Util.classExists("com.amazonaws.services.s3.AmazonS3");
    public static final String HDFS_PROTOCOL = "hdfs";
    public static final boolean HDFS_ENABLED = Util.classExists("org.apache.hadoop.fs.FileSystem");
    public static final Pattern HDFS_PATTERN = Pattern.compile("^(hdfs:\\/\\/)(?:[^@\\/\\n]+@)?([^\\/\\n]+)");

    public static CountingReader readerFor(String fileName) throws IOException {
        apocConfig().checkReadAllowed(fileName);
        if (fileName==null) return null;
        fileName = changeFileUrlIfImportDirectoryConstrained(fileName);
        if (fileName.matches("^\\w+:/.+")) {
            if (isHdfs(fileName)) {
                return readHdfs(fileName);
            } else {
                return Util.openInputStream(fileName,null,null).asReader();
            }
        }
        return readFile(fileName);
    }
    public static CountingInputStream inputStreamFor(String fileName) throws IOException {
        apocConfig().checkReadAllowed(fileName);
        if (fileName==null) return null;
        fileName = changeFileUrlIfImportDirectoryConstrained(fileName);
        if (fileName.matches("^\\w+:/.+")) {
            if (isHdfs(fileName)) {
                return readHdfsStream(fileName);
            } else {
                return Util.openInputStream(fileName,null,null);
            }
        }
        return readFileStream(fileName);
    }

    private static CountingInputStream readHdfsStream(String fileName) {
        try {
            StreamConnection streamConnection = HDFSUtils.readFile(fileName);
            return new CountingInputStream(streamConnection.getInputStream(), streamConnection.getLength());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static CountingReader readHdfs(String fileName) {
        try {
            StreamConnection streamConnection = HDFSUtils.readFile(fileName);
            Reader reader = new BufferedReader(new InputStreamReader(streamConnection.getInputStream(), "UTF-8"));
            return new CountingReader(reader, streamConnection.getLength());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static CountingReader readFile(String fileName) throws IOException, FileNotFoundException {
        File file = new File(fileName);
        if (!file.exists() || !file.isFile() || !file.canRead()) throw new IOException("Cannot open file "+fileName+" for reading.");
        return new CountingReader(file);
    }

    private static CountingInputStream readFileStream(String fileName) throws IOException, FileNotFoundException {
        File file = new File(fileName);
        if (!file.exists() || !file.isFile() || !file.canRead()) throw new IOException("Cannot open file "+fileName+" for reading.");
        return new CountingInputStream(file);
    }

    public static String changeFileUrlIfImportDirectoryConstrained(String url) throws IOException {
        if (isFile(url) && isImportUsingNeo4jConfig()) {
            if (!apocConfig().getBoolean(APOC_IMPORT_FILE_ALLOW__READ__FROM__FILESYSTEM))
                throw new RuntimeException("Import file "+url+" not enabled, please set dbms.security.allow_csv_import_from_file_urls=true in your neo4j.conf");

            URI uri = URI.create(url);
            String path = uri.getPath();
            if (path.isEmpty()) {
                path = uri.getHost(); // in case of file://test.csv
            }
            Path normalized = Paths.get(path).normalize(); // get rid of ".." et.al.

            File result;
            if (apocConfig().isImportFolderConfigured()) {
                Path importDir = Paths.get(apocConfig().getString("dbms.directories.import")).toAbsolutePath();

                result = normalized.startsWith(importDir) ?
                        normalized.toFile() :
                        // use subpath to strip off leading "/" from normalized
                        new File(importDir.toFile(), normalized.subpath(0, normalized.getNameCount()).toString());

            } else {
                result = normalized.toFile();
            }
            return result.toURI().toString();
        }
        return url;
    }

    public static boolean isFile(String fileName) {
        if (fileName==null) return false;
        if (fileName.toLowerCase().startsWith("http")) return false;
        if (isHdfs(fileName)) return false;
        if (fileName.toLowerCase().startsWith("file:")) return true;
        return true;
    }

    public static PrintWriter getPrintWriter(String fileName, Writer out) throws IOException {
        OutputStream outputStream = getOutputStream(fileName, new WriterOutputStream(out, Charset.defaultCharset()));
        return outputStream == null ? null : new PrintWriter(outputStream);
    }

    public static OutputStream getOutputStream(String fileName, OutputStream out) throws IOException {
        if (fileName == null) return null;
        OutputStream outputStream;
        if (isHdfs(fileName)) {
            try {
                outputStream = HDFSUtils.writeFile(fileName);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            outputStream = getOrCreateOutputStream(fileName, out);
//            outputStream = fileName.equals("-") ? out : new FileOutputStream(fileName);
        }
        return new BufferedOutputStream(outputStream);
    }

    private static OutputStream getOrCreateOutputStream(String fileName, OutputStream out) throws FileNotFoundException, MalformedURLException {
        OutputStream outputStream;
        if (fileName.equals("-")) {
            outputStream = out;
        } else {
            boolean enabled = isImportUsingNeo4jConfig();
            if (enabled) {
                String importDir = apocConfig().getString("dbms.directories.import", "import");
                File file = new File(importDir, fileName);
                outputStream = new FileOutputStream(file);
            } else {
                URI uri = URI.create(fileName);
                outputStream = new FileOutputStream(uri.isAbsolute() ? uri.toURL().getFile() : fileName);
            }
        }
        return outputStream;
    }

    private static boolean isImportUsingNeo4jConfig() {
        return apocConfig().getBoolean(ApocConfig.APOC_IMPORT_FILE_USE_NEO4J_CONFIG);
    }

    public static StreamConnection openS3InputStream(URL url) throws IOException {
        if (!S3_ENABLED) {
            throw new MissingDependencyException("Cannot find the S3 jars in the plugins folder. \n" +
                    "Please put these files into the plugins folder :\n\n" +
                    "aws-java-sdk-core-x.y.z.jar\n" +
                    "aws-java-sdk-s3-x.y.z.jar\n" +
                    "httpclient-x.y.z.jar\n" +
                    "httpcore-x.y.z.jar\n" +
                    "joda-time-x.y.z.jar\n" +
                    "\nSee the documentation: https://neo4j-contrib.github.io/neo4j-apoc-procedures/#_loading_data_from_web_apis_json_xml_csv");
        }
        return S3URLConnection.openS3InputStream(url);
    }

    public static StreamConnection openHdfsInputStream(URL url) throws IOException {
        if (!HDFS_ENABLED) {
            throw new MissingDependencyException("Cannot find the HDFS/Hadoop jars in the plugins folder. \n" +
                    "Please put these files into the plugins folder :\n\n" +
                    "commons-cli\n" +
                    "hadoop-auth\n" +
                    "hadoop-client\n" +
                    "hadoop-common\n" +
                    "hadoop-hdfs\n" +
                    "htrace-core-3.1.0-incubating\n" +
                    "protobuf-java\n" +
                    "\nSee the documentation: https://neo4j-contrib.github.io/neo4j-apoc-procedures/#_loading_data_from_web_apis_json_xml_csv");
        }
        return HDFSUtils.readFile(url);
    }

    public static boolean isHdfs(String fileName) {
        Matcher matcher = HDFS_PATTERN.matcher(fileName);
        return matcher.find();
    }

    /**
     * @returns a File pointing to Neo4j's log directory, if it exists and is readable, null otherwise.
     */
    public static File getLogDirectory() {
        String neo4jHome = apocConfig().getString("unsupported.dbms.directories.neo4j_home", "");
        String logDir = apocConfig().getString("dbms.directories.logs", "");

        File logs = logDir.isEmpty() ? new File(neo4jHome, "logs") : new File(logDir);

        if (logs.exists() && logs.canRead() && logs.isDirectory()) {
            return logs;
        }

        return null;
    }

    /**
     * @return a File representing the metrics directory that is listable and readable, or null if metrics don't exist,
     * aren't enabled, or aren't readable.
     */
    public static File getMetricsDirectory() {
        String neo4jHome = apocConfig().getString("unsupported.dbms.directories.neo4j_home", "");
        String metricsSetting = apocConfig().getString("dbms.directories.metrics", "");

        File metricsDir = metricsSetting.isEmpty() ? new File(neo4jHome, "metrics") : new File(metricsSetting);

        if (metricsDir.exists() && metricsDir.canRead() && metricsDir.isDirectory() ) {
            return metricsDir;
        }

        return null;
    }

    // This is the list of dbms.directories.* valid configuration items for neo4j.
    // https://neo4j.com/docs/operations-manual/current/reference/configuration-settings/
    // Usually these reside under the same root but because they're separately configurable, in the worst case
    // every one is on a different device.
    //
    // More likely, they'll be largely similar metrics.
    public static final List<String> NEO4J_DIRECTORY_CONFIGURATION_SETTING_NAMES = Arrays.asList(
            "dbms.directories.certificates",
            "dbms.directories.data",
            "dbms.directories.import",
            "dbms.directories.lib",
            "dbms.directories.logs",
//            "dbms.directories.metrics",  // metrics is only in EE
            "dbms.directories.plugins",
            "dbms.directories.run",
            "dbms.directories.tx_log",
            "unsupported.dbms.directories.neo4j_home"
    );

    public static void closeReaderSafely(CountingReader reader) {
        if (reader != null) {
            try { reader.close(); } catch (IOException ignored) { }
        }
    }
}
