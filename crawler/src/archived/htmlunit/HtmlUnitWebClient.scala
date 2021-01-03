package com.adcoelum.client.htmlunit

import java.io.{ByteArrayInputStream, IOException, InputStream}
import java.net.{MalformedURLException, URL}
import java.util.Date

import com.adcoelum.client.{ClientRegistry, HttpClient, Page}
import com.gargoylesoftware.htmlunit.html.{HTMLParserListener, HtmlPage}
import com.gargoylesoftware.htmlunit.javascript.JavaScriptErrorListener
import com.gargoylesoftware.htmlunit.util._
import com.gargoylesoftware.htmlunit.{BrowserVersion, _}


class HtmlUnitWebClient extends HttpClient {
    val client = new WebClient(BrowserVersion.CHROME)

    val webConnectionFileMatcher = ("^.*css.*$|" +
        "^(?!.*(jquery|aurelia|angular|react|backbone|ember|meteor|polymer|knockout|vue|mercury|underscore|prototype|phantom|grunt|babel)).*js.*$|" +
        "^.*jpg.*$|" +
        "^.*gif.*$|" +
        "^.*png.*$").r

    val webConnectionCacheMatcher = "(javascript|ecmascript)".r

    newClient

    def get(url: URL): Page = {
        val result: HtmlPage = client.getPage[HtmlPage](url)
        client.waitForBackgroundJavaScript(1000)
        val rawPage: HtmlUnitPage = new HtmlUnitPage(result)
        rawPage
    }

    def close = {
        client.close
    }

    def shutdown = {
        client.close
    }

    def newClient = {
        client.getOptions.setCssEnabled(false)
        client.getOptions.setAppletEnabled(false)
        client.getOptions.setGeolocationEnabled(false)
        client.getOptions.setThrowExceptionOnScriptError(false)
        client.getOptions.setActiveXNative(false)
        client.getOptions.setDoNotTrackEnabled(false)

        client.getOptions.setJavaScriptEnabled(true)
        client.getOptions.setRedirectEnabled(true)
        client.getOptions.setPopupBlockerEnabled(true)
        client.getOptions.setUseInsecureSSL(true)
        client.getOptions.setThrowExceptionOnFailingStatusCode(true)

        client.setAjaxController(new NicelyResynchronizingAjaxController)
        /*
        client.setAjaxController(new AjaxController() {
            override def processSynchron(page: HtmlPage, request: WebRequest, async: Boolean): Boolean = {
                return true;
            }
        })
        */

        client.setJavaScriptTimeout(10000)
        client.getJavaScriptEngine.getContextFactory.enterContext.setOptimizationLevel(9)

        class HardenedCache extends com.gargoylesoftware.htmlunit.Cache {
            override def clear: Unit = {}

            override def cacheIfPossible(request: WebRequest, response: WebResponse, toCache: Object): Boolean = {
                if (this.getSize < this.getMaxSize)
                    super.cacheIfPossible(request, response, toCache)
                else
                    false
            }

            override def isCacheable(request: WebRequest, response: WebResponse): Boolean = {
                HttpMethod.GET == response.getWebRequest.getHttpMethod && !isDynamicContent(response) &&
                    (response.getContentType.contains("javascript") || response.getContentType.contains("ecmascript"))
            }

            def isDynamicContent(response: WebResponse): Boolean = {
                val lastModified: Date = parseDateHeader(response, "Last-Modified")
                val expires: Date = parseDateHeader(response, "Expires")

                val delay: Long = 10 * 60000 // 10 * Number of milliseconds per minute
                val now: Long = getCurrentTimestamp()

                val cacheableContent: Boolean = (expires != null && (expires.getTime - now > delay)
                    || (expires == null && lastModified != null && (now - lastModified.getTime > delay)))

                !cacheableContent
            }
        }

        client.setCache(new HardenedCache)
        client.getCache.setMaxSize(50)

        class WebResponseDataNoGzip(body: Array[Byte], statusCode: Integer, statusMessage: String,
                                    responseHeaders: java.util.List[NameValuePair])
            extends WebResponseData(body, statusCode, statusMessage, responseHeaders) {
            @throws(classOf[IOException])
            override def getInputStream(): InputStream = {
                val stream: InputStream = new ByteArrayInputStream(body)

                if (stream == null)
                    return null

                stream
            }
        }


        new WebConnectionWrapper(client) {
            override def getResponse(request: WebRequest): WebResponse = {
                // val file =

                //if (request.getUrl.getHost != Configuration.rootUrl)
                //	new StringWebResponse("", request.getUrl)
                if (request.getHttpMethod == HttpMethod.GET && !allowedToGET())
                    new StringWebResponse("", request.getUrl)
                else if (request.getHttpMethod == HttpMethod.POST && !allowedToPOST())
                    new StringWebResponse("", request.getUrl)
                else if (webConnectionFileMatcher.findAllIn(request.getUrl.getFile).isEmpty) {
                    val response: WebResponse = super.getResponse(request)
                    val responseHeaders: java.util.List[NameValuePair] = response.getResponseHeaders

                    // Clean up response by replacing scrolling
                    val modifiedResponseText = response.getContentAsString
                        .replaceAll("[,][:]x", "")
                    //.replaceAll("[.]scroll[(]", ".keypress(")

                    new WebResponse(new WebResponseDataNoGzip(modifiedResponseText.getBytes, response.getStatusCode, response.getStatusMessage,
                        responseHeaders), request.getUrl, request.getHttpMethod, response.getLoadTime)

                }
                else
                    new StringWebResponse("", request.getUrl)
            }
        }

        java.util.logging.Logger.getLogger("com.gargoylesoftware.htmlunit").setLevel(java.util.logging.Level.OFF)
        java.util.logging.Logger.getLogger("org.apache.commons.httpclient").setLevel(java.util.logging.Level.OFF)


        client.setIncorrectnessListener(new IncorrectnessListener() {

            override def notify(message: String, origin: scala.Any): Unit = {}
        })

        client.setJavaScriptErrorListener(new JavaScriptErrorListener() {

            override def timeoutError(page: InteractivePage, allowedTime: Long, executionTime: Long): Unit = {}

            override def malformedScriptURL(page: InteractivePage, url: String, malformedURLException: MalformedURLException): Unit = {}

            override def loadScriptError(page: InteractivePage, scriptUrl: URL, exception: Exception): Unit = {}

            override def scriptException(page: InteractivePage, scriptException: ScriptException): Unit = {}
        })

        client.setHTMLParserListener(new HTMLParserListener() {

            override def warning(message: String, url: URL, html: String, line: Int, column: Int, key: String): Unit = {}

            override def error(message: String, url: URL, html: String, line: Int, column: Int, key: String): Unit = {}
        })

        ClientRegistry.add(this)
    }

    def waitForAjax = {

    }
}
