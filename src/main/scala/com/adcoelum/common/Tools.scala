package com.adcoelum.common

import java.io._
import java.lang.management.ManagementFactory
import java.net.{Socket, URL}
import java.util.zip.{GZIPInputStream, GZIPOutputStream}

import com.adcoelum.data.{Cache, Collection}
import com.adcoelum.network.Client
import org.htmlcleaner.{CleanerProperties, HtmlCleaner, PrettyXmlSerializer}

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.xml._
import scalaj.http._
import scala.reflect.runtime.universe._
import scala.language.implicitConversions


/**
  * The tools object provides useful helper methods to perform specific utility tasks that may be required across multiple
  * classes and therefore do not make sense to implement separately in multiple places.  This object centralises these
  * helper methods, particular useful for actor classes using the methods
  */
object Tools {
    protected val queryIdentifier = "?"
    protected val stringType = "UTF-8"

    /**
      * This special helper object configures the html cleaner class to perform a clean up of an html page so that
      * it is ready for xml processing use, removing problematic tags, poorly written html and any other quirks that would
      * break an xml read of a poorly written html document
      */
    protected object HtmlXmlify {
        val htmlCleaner = new HtmlCleaner
        setupCleaner(htmlCleaner)

        val cleaner = new PrettyXmlSerializer(htmlCleaner.getProperties)

        /**
          * The setup cleaner method initialises all variables necessary for processing html as xml
          *
          * @param cleaner
          */
        def setupCleaner(cleaner: HtmlCleaner) = {
            val properties: CleanerProperties = cleaner.getProperties
            // http://htmlcleaner.sourceforge.net/parameters.php

            properties.setTranslateSpecialEntities(false)
            properties.setTransResCharsToNCR(false)
            properties.setTreatUnknownTagsAsContent(false)
            properties.setOmitDeprecatedTags(false)
            properties.setAllowHtmlInsideAttributes(false)

            properties.setIgnoreQuestAndExclam(true)
            properties.setUseEmptyElementTags(true)
            properties.setAdvancedXmlEscape(true)
            properties.setRecognizeUnicodeChars(true)
            properties.setOmitComments(true)
            properties.setAdvancedXmlEscape(true)
            properties.setKeepWhitespaceAndCommentsInHead(true)
            properties.setRecognizeUnicodeChars(true)
            properties.setUseCdataForScriptAndStyle(true)

            properties.setPruneTags("noscript,script,style,iframe")
        }
    }

    /**
      * The structure html method takes unstructured, poorly written html and converts it to a well formed xml document as a string
      *
      * @param html
      * @return
      */
    def structureHtml(html: String): String = {
        HtmlXmlify.cleaner.getAsString(html).replaceAll("[\\s]+", " ")
    }

    /**
      * The html to xml method takes a clean html document (as a string) and converts it structured xml
      *
      * @param html
      * @return
      */
    def htmlToXml(html: String): Elem = {
        XML.loadString(html)
    }

    /**
      * Convert a url to a string idenifier for naming and unique identification use
      *
      * @param url
      * @return
      */
    def urlToIdentifier(url: URL): String = {
        val path = {
            if (!url.getPath.startsWith("/"))
                "/" + url.getPath
            else
                url.getPath
        }
        var fullPath = url.getHost + path + queryIdentifier
        if (url.getQuery != null) fullPath += url.getQuery
        fullPath
    }

    /**
      * Convert a url to a string containing solely the path and any query string
      *
      * @param url
      * @return
      */
    def urlToPathQuery(url: URL): String = {
        val path = {
            if (!url.getPath.startsWith("/"))
                "/" + url.getPath
            else
                url.getPath
        }
        var pathQuery = path + queryIdentifier
        if (url.getQuery != null) pathQuery += url.getQuery
        pathQuery
    }

    /**
      * This method is used to reduce the size of a string by performing a gzip compression to the string, with a string as output
      *
      * @param str
      * @return
      */
    def stringCompress(str: String): String = {
        // Determine whether to apply string compression
        if (!Configuration.textCompression)
            return str

        // Setup the compressor
        val out: ByteArrayOutputStream = new ByteArrayOutputStream
        val gzip: GZIPOutputStream = new GZIPOutputStream(out)

        // Compress the string
        gzip.write(str.getBytes())
        gzip.close

        // Output as a string
        val outStr = out.toString("ISO-8859-1")
        out.close

        outStr
    }

    /**
      * This is used to re-expand a string by performing a gzip decompression to a string, with the original string as output
      *
      * @param str
      * @return
      */
    def stringDecompress(str: String): String = {
        // Determine whether to apply string decompression
        if (!Configuration.textCompression)
            return str

        // Setup the decompressor
        val gis: GZIPInputStream = new GZIPInputStream(new ByteArrayInputStream(str.getBytes("ISO-8859-1")))
        val bf: BufferedReader = new BufferedReader(new InputStreamReader(gis, "ISO-8859-1"))

        // Decompress the string and output
        val outStr = Iterator.continually(bf.readLine()).takeWhile(_ != null).mkString
        gis.close
        bf.close

        outStr
    }

    /**
      * A helper method to test whether a port is in use
      *
      * @param portNumber
      * @param hostName
      * @return
      */
    def isPortInUse(portNumber: Integer, hostName: String = "localhost"): Boolean = {
        try {

            val s: Socket = new Socket(hostName, portNumber)
            s.close
            true

        }
        catch {
            case e: Exception => false
        }
    }

    /**
      * This method is used to find a free/avaialble port that can be used for listening, starting at port 9,999
      *
      * @return
      */
    def findFreePort: Integer = {
        while (true) {
            val minimum = (Configuration.config \ "network" \ "portMin").as[Int]
            val maximum = (Configuration.config \ "network" \ "portMax").as[Int]

            val port: Integer = scala.math.abs(scala.util.Random.nextInt % (maximum - minimum)) + minimum
            if (!isPortInUse(port))
                return port
        }

        0
    }

    /**
      * This method performs checks for critical services that are important to the functioning of a crawler and/or
      * indexer or other instances
      *
      * @return
      */
    def ensureConnectivity: Boolean = {
        // Cache
        if (!(new Cache).checkConnectivity) {
            Logger.error("Failed to confirm connectivity to cache", error = throw new Exception("No cache connectivity"))
            return false
        }

        // Collections
        if (!(new Collection).checkConnectivity) {
            Logger.error("Failed to confirm connectivity to collection", error = throw new Exception("No collection connectivity"))
            return false
        }

        // Local Geocoder
        try {
            Http(Configuration.geoCoder("photon_source") + "/api")
                .param("limit", "1")
                .param("osm_value", "residential")
                .param("q", "Test")
                .option(HttpOptions.connTimeout(5000))
                .option(HttpOptions.readTimeout(5000))
                .asString
        }
        catch {
            case e: Exception => {
                Logger.warn("Local geocoder not available - check connectivity", error = e)
            }
        }

        Logger.info("Connectivity up as required")

        true
    }

    def getProcessID: Int = {
        val processString = ManagementFactory.getRuntimeMXBean.getName
        val process = processString.split("@")(0)
        process.toInt
    }

    def sleep(duration: FiniteDuration) = {
        Thread.sleep(duration.toMillis)
    }

    /**
      *
      * @param host
      * @param port
      * @param retries
      */
    def testConnectivity(host: String, port: Int, retries: Int = 5, delay: FiniteDuration = 5 seconds, errorMessage: String = ""): Boolean = {
        var attempt = 0
        var success = false

        while (!success && attempt < retries) {
            try {
                // Make a client to test connectivity
                val client = new Client(port, host)
                success = client.connect
                attempt += 1
                Tools.sleep(1 second)
            }
            catch {
                case e: Exception => {
                    attempt += 1
                    Logger.warn(s"Unable to connect to host ${host} on port ${port} ${errorMessage}", error = e)
                    Tools.sleep(delay)
                }
            }
        }

        success
    }

    def bytesString(bytes: Long, si: Boolean = false): String = {
        val unit: Int = {
            if (si) 1000
            else 1024
        }

        if (bytes < unit)
            return bytes + " B"

        val exp: Int = (Math.log(bytes) / Math.log(unit)).toInt
        val pre: String = {
            (if (si) "kMGTPE"
            else "KMGTPE").charAt(exp-1) +
                (if (si) ""
                else "i")
        }

        "%.1f %sB".format(bytes / Math.pow(unit, exp), pre)
    }


    def getListOfFiles(dir: String): List[File] = {
        val d = new File(dir)
        if (d.exists && d.isDirectory) {
            d.listFiles.filter(_.isFile).toList
        } else {
            List[File]()
        }
    }

    def systemOS = {
        val os = System.getProperty("os.name").toLowerCase

        os match {
            case win if win.contains("win") => "windows"
            case mac if mac.contains("mac") => "mac"
            case sol if sol.contains("sunos") => "sunos"
            case nix if nix.contains("nix") || nix.contains("nux") || nix.contains("aix") => "linux"
        }
    }

    def serialize(obj: Any): Array[Byte] = {
        // serialize the object
        try {
            val bo: ByteArrayOutputStream = new ByteArrayOutputStream
            val so: ObjectOutputStream = new ObjectOutputStream(bo)
            so.writeObject(obj)
            so.flush
            return bo.toByteArray
        }
        catch {
            case e: Exception => {
                Logger.warn(s"Failed to serialize object ${obj}", error = e)
                new Array[Byte](0)
            }
        }
    }

    def deserialize[X:TypeTag](bArray: Array[Byte]): X = {
        // deserialize the object
        try {
            val bi: ByteArrayInputStream = new ByteArrayInputStream(bArray)
            val si: ObjectInputStream = new ObjectInputStream(bi)
            return si.readObject.asInstanceOf[X]
        }
        catch {
            case e: Exception => Logger.warn(s"Failed to deserialize object ${bArray}", error = e)
            null.asInstanceOf[X]
        }
    }



    // https://gist.github.com/stepancheg/167566
    object XmlHelpers {
        val docBuilder =
            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
    }

    class NodeExtras(n: Node) {
        def toJdkNode(doc: org.w3c.dom.Document): org.w3c.dom.Node =
            n match {
                case Elem(prefix, label, attributes, scope, children @ _*) =>
                    // XXX: ns
                    val r = doc.createElement(label)
                    for (a <- attributes) {
                        r.setAttribute(a.key, a.value.text)
                    }
                    for (c <- children) {
                        r.appendChild(new NodeExtras(c).toJdkNode(doc))
                    }
                    r
                case Text(text) => doc.createTextNode(text)
                case Comment(comment) => doc.createComment(comment)
                // not sure
                case a: Atom[_] => doc.createTextNode(a.data.toString)
                // XXX: other types
                //case x => throw new Exception(x.getClass.getName)
            }
    }

    class ElemExtras(e: Elem) extends NodeExtras(e) {
        override def toJdkNode(doc: org.w3c.dom.Document) =
            super.toJdkNode(doc).asInstanceOf[org.w3c.dom.Element]

        def toJdkDoc = {
            val doc = XmlHelpers.docBuilder.newDocument()
            doc.appendChild(toJdkNode(doc))
            doc
        }
    }

    implicit def nodeExtras(n: Node) = new NodeExtras(n)
    implicit def elemExtras(e: Elem) = new ElemExtras(e)
}
