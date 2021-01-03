package com.adcoelum.client.firefox

import java.net.URL
import java.util
import java.util.concurrent.TimeUnit
import java.io.{File, FileReader, Reader}

import com.adcoelum.client.{ClientRegistry, HttpClient, Page}
import com.adcoelum.common.{Configuration, Logger, Tools}
import com.adcoelum.downloader.Downloader
import com.adcoelum.error.DownloadTimeoutException
import com.google.common.collect.{ImmutableList, ImmutableMap}
import org.openqa.selenium.firefox._
import org.openqa.selenium.remote.service.{DriverCommandExecutor, DriverService}
import org.openqa.selenium.remote.{CapabilityType, DesiredCapabilities, RemoteWebDriver}
import org.openqa.selenium.{By, Keys, WebDriverException, WebElement}
import org.openqa.selenium.support.ui.{ExpectedConditions, WebDriverWait}

import scala.concurrent.duration._
import scala.util.Random
import scala.language.postfixOps
import scala.collection.JavaConverters._
import sys.process._

/**
  * Created by jeremy on 29/01/2017.
  */
class FirefoxWebClient extends HttpClient {
    var browserID = (Configuration.config \ "downloader" \ "browser" \ "browserName").as[String] + "_" + Random.nextLong.abs.toString
    var reloadBrowser = (Configuration.config \ "downloader" \ "browser" \ "reloadBrowser").as[Boolean]
    var multiBrowser = (Configuration.config \ "downloader" \ "browser" \ "multiBrowser").as[Boolean]
    var multiBrowserMultiWindow = (Configuration.config \ "downloader" \ "browser" \ "multiBrowserMultiWindow").as[Boolean]
    var reloadEveryXPages = (Configuration.config \ "downloader" \ "browser" \ "reloadEveryXPages").as[Int]
    Logger.info(s"Creating web client with ID: ${browserID}")

    val defaultTimeout = (Configuration.config \ "downloader" \ "browser" \ "actionWaitTimeout").as[Int]
    val pageLoadTimeout = (Configuration.config \ "downloader" \ "browser" \ "pageLoadTimeout").as[Int]
    val scriptLoadTimeout = (Configuration.config \ "downloader" \ "browser" \ "scriptLoadTimeout").as[Int]
    var internalTimeout = (Configuration.config \ "downloader" \ "browser" \ "actionWaitInternalTimeout").as[Int]
    var conditionalTimeout = (Configuration.config \ "downloader" \ "browser" \ "conditionalTimeout").as[Int]
    var conditionalPolling = (Configuration.config \ "downloader" \ "browser" \ "conditionalPolling").as[Int]
    var reloadCounter = 0


    var driver: DriverService = _
    var client: RemoteWebDriver = _
    var waiter: WebDriverWait = _
    var window: String = ""

    // Register browser with proxy
    Downloader.proxy.registerBrowser(browserID)

    // Register this client with client registry
    ClientRegistry.add(this)

    /*
    // Establish client
    if (multiBrowser)
        newClient
    */

    /**
      * Method to establish a new client using basic settings with default time metrics
      */
    def newClient = {
        Logger.debug(s"Creating browser for web client with ID: ${browserID}")

        // Stopping any existing driver service
        if (driver != null && client != null) {
            client.quit
            driver.stop
        }

        // Establish the browser instance
        // client = new FirefoxDriver(FirefoxWebConfig.service, FirefoxWebConfig.settings(browserID), FirefoxWebConfig.settings(browserID))
        driver = FirefoxWebConfig.service
        client = new RemoteWebDriver(new DriverCommandExecutor(driver), FirefoxWebConfig.settings(browserID))

        // Setup default timeout data
        client.manage.timeouts.pageLoadTimeout(pageLoadTimeout, TimeUnit.SECONDS)
        client.manage.timeouts.setScriptTimeout(scriptLoadTimeout, TimeUnit.SECONDS)

        // Wait for driver to load
        waiter = new WebDriverWait(client, 30, 500)
        waiter.withTimeout(conditionalTimeout, TimeUnit.SECONDS)
        waiter.pollingEvery(conditionalPolling, TimeUnit.MILLISECONDS)

        // Perform a temporary pause to help with window allocation
        Tools.sleep(scala.util.Random.nextInt(5000) milliseconds)
    }


    def setupWindows = {
        // Multi Browser case with close window methodology
        if (multiBrowser && multiBrowserMultiWindow) {
            if (client.getWindowHandles.asScala.size < 2) {
                Logger.info(s"Commencing opening new windows as needed")
                val windowsNeeded = 2 - client.getWindowHandles.asScala.size

                // Create the necessary windows for processing
                client.switchTo.window(client.getWindowHandles.asScala.head)
                1 to windowsNeeded foreach { count =>
                    Logger.info(s"Opening additional window ${count} for multi browser with window closing scenario")
                    client.executeScript("window.open('about:blank', '_blank');")
                }
            }

            window = client.getWindowHandles.asScala.last
            Logger.info(s"Browser ${browserID} using selected window for use: ${window}")
            client.switchTo.window(window)
        }

        // Single browser, multiple windows case
        if (!multiBrowser && !client.getWindowHandles.asScala.contains(window)) {
            Logger.info(s"Commencing opening new windows as needed")
            val currentWindowCount = client.getWindowHandles.size
            val workers = Configuration.downloaderWorkerRoutees
            val windowsNeeded = workers + 1 - currentWindowCount

            // Create the necessary windows for processing
            client.switchTo.window(client.getWindowHandles.asScala.head)
            1 to windowsNeeded foreach { count =>
                Logger.info(s"Opening additional window ${count} for single browser multi window scenario")
                client.executeScript("window.open('about:blank', '_blank');")
            }

            // Define window for client
            var counter = 0
            var allocated = false

            client.getWindowHandles.asScala foreach { aWindow =>
                var found = false

                if (!allocated) {
                    ClientRegistry.clients foreach { aClient =>
                        if (aClient.handle == aWindow)
                            found = true
                    }

                    if (counter != 0 && !found) {
                        window = client.getWindowHandles.asScala.toList(counter)
                        allocated = true
                    }
                }

                counter += 1
            }

            // Switch to the window
            client.switchTo.window(window)
            Logger.info(s"Browser ${browserID} using selected window for use: ${window}")
            Logger.info(s"List of all windows open: ${client.getWindowHandles.asScala}")
        }
    }

    /**
      * Return the ID of the browser
      *
      * @return
      */
    def id: String = {
        browserID
    }

    /**
      * Return the window handle being used
      * @return
      */
    def handle: String = {
        window
    }

    /**
      * Return the status code for the page downloaded from the browser
      *
      * @return
      */
    def status: Int = {
        Downloader.proxy.getStatusCode(new URL(client.getCurrentUrl))
    }

    /**
      * Method to get a page using the HTTP GET method
      * @param url
      * @return
      */
    def get(url: URL): Page = {
        var isDownloading = false

        // Create a request url including the browser ID
        val requestUrl = {
            val protocol = url.getProtocol
            val urlString = s"${protocol}://${Tools.urlToIdentifier(url)}"
            urlString
        }

        val downloadThread = new Thread(new Runnable() {
            def run = {
                // Test to make sure the browser is not dead
                try {
                    client.toString

                    if (client.getSessionId == null || !driver.isRunning)
                        throw new Exception

                    if (reloadBrowser)
                        reloadCounter += 1
                }
                catch {
                    case e: Exception => {
                        // Establish client
                        Logger.debug(s"Discovered no active browser - launching a new browser connection for client $browserID")
                        newClient

                        if (reloadBrowser)
                            reloadCounter = 1
                    }
                }

                setupWindows

                // Reset tracker for proxy to help establish if all items have been queried/captured in proxy
                Downloader.proxy.resetTracker(browserID)

                // Get the page using the browser
                Logger.trace(s"Performing get request from Firefox driver: ${requestUrl}")
                isDownloading = true
                client.get(Thread.currentThread.getName)
                Logger.trace(s"Downloaded page from browser ${browserID} with url ${requestUrl}")
                isDownloading = false
            }
        }, requestUrl)

        downloadThread.start

        // Manage timeout for the page
        try {
            downloadThread.join(pageLoadTimeout * 1000)
        }
        catch {
            case e: InterruptedException =>
        }

        if (isDownloading)
        { // Thread still alive, we need to abort
            val exception = new DownloadTimeoutException
            Logger.warn(s"Timeout while attempting get from browser ${browserID} for url ${requestUrl}", error = exception)
            downloadThread.interrupt
            isDownloading = false
            throw exception
        }


        client.manage.window.maximize

        // Wait until HTML tag is present
        waitForActions
        waiter.until(ExpectedConditions.presenceOfElementLocated(By.tagName("html")))
        waiter.withTimeout(conditionalTimeout, TimeUnit.SECONDS)
        waiter.pollingEvery(conditionalPolling, TimeUnit.MILLISECONDS)

        // Capture response URL
        val responseUrl = new URL(client.getCurrentUrl)
        // Capture instance handle
        val instance = client.getWindowHandle

        // Create page element and return
        val page: WebElement = client.findElementByXPath("html")
        new FirefoxPage(url, responseUrl, page, this, instance)
    }

    /**
      * Method to close browser window
      */
    def close = {
        try {
            if (multiBrowser && multiBrowserMultiWindow)
                client.close

            if (reloadBrowser) {
                if (reloadCounter >= reloadEveryXPages) {
                    Logger.debug(s"Closing browser client due to instruction to reload every $reloadEveryXPages pages for client $browserID")
                    client.quit
                    reloadCounter = 0
                }
            }
        }
        catch {
            case e: Exception => Logger.warn("Attempted browser close but browser is already dead", error = e)
        }

    }

    /**
      *
      */
    def reset = {
        try {
            client.quit
        }
        catch {
            case e: Exception => Logger.warn("Attempted browser close but browser is already dead", error = e)
        }

        if (reloadBrowser)
            reloadCounter = 0
    }


    /**
      * Method to shutdown (kill) the browser
      */
    def shutdown = {
        try {
            // Close the operating window
            client.close
        }
        catch {
            case e: Exception => Logger.warn(s"Failed to close browser for client ${browserID}", error = e)
        }
    }

    def forward = {
        // Reset tracker for proxy to help establish if all items have been queried/captured in proxy
        Downloader.proxy.resetTracker(browserID)

        client.navigate.forward
    }


    def back = {
        // Reset tracker for proxy to help establish if all items have been queried/captured in proxy
        Downloader.proxy.resetTracker(browserID)

        client.navigate.back
    }


    /**
      * Method to wait for Ajax response
      * This is critical for pages that perform ajax processing upon a user action and/or on load
      * The browser will wait a specified amount of time to capture changes to the page and then allow for progress
      * This method has been developed heuristically to capture changes to a page as a result of ajax actions
      */
    def waitForActions: Unit = {
        if ((Configuration.config \ "downloader" \ "waitForActions").as[Boolean]) {
            // Initially wait 1/2 second for first time processing to allow an Ajax request to be made
            Tools.sleep(0.5 seconds)

            // Check if the proxy has no requests made from the browser for Ajax
            if (Downloader.proxy.trackerSize(browserID) == 0)
                return

            var counter = 0
            Logger.trace(s"Starting actions wait on ${browserID}", Logger.SUPER_VERBOSE_INTENSITY)

            // Establish baseline for response from the server
            var previousResponseCount = Downloader.proxy.trackerSize(browserID)

            // Start testing wait time
            while (counter < defaultTimeout) {
                // Increment counter
                counter += 1

                // Track current response count
                val currentResponseCount = Downloader.proxy.trackerSize(browserID)

                // If tracker is zero or less
                if (previousResponseCount <= 0) {
                    // Commence internal count just in case there is a state change subsequently
                    var internalCounter = 0

                    // Start internal counter
                    while (internalCounter < internalTimeout) {
                        // Increment counter and sleep for 1 second
                        internalCounter += 1
                        Tools.sleep(1 second)
                        Logger.trace(s"Waiting for internal counter in actions wait on ${browserID} at ${internalCounter} seconds", Logger.SUPER_VERBOSE_INTENSITY)

                        // If state has changed, jump out as Ajax is unfinished
                        if (Downloader.proxy.trackerSize(browserID) > 0)
                            internalCounter = internalTimeout + 1 // Jump out but prevent return as there is more to process
                    }

                    // Signal waiting has finished
                    if (internalCounter == internalTimeout) {
                        Logger.trace(s"Waiting for actions finished on ${browserID}", Logger.SUPER_VERBOSE_INTENSITY)
                        return
                    }
                }

                // Wait for 1 second
                previousResponseCount = currentResponseCount
                Logger.trace(s"Waiting for 1 second after ${counter - 1} seconds for actions wait on ${browserID}")
                Tools.sleep(1 second)
            }
        }
    }

    def cleanUpExtraWindows = {
        if (!multiBrowser) {
            if (client.getWindowHandles.asScala.size > Configuration.downloaderWorkerRoutees + 1) {
                Configuration.downloaderWorkerRoutees + 2 to client.getWindowHandles.asScala.size foreach { windowCount =>
                    Logger.trace("Closing excess windows", Logger.SUPER_VERBOSE_INTENSITY)
                    client.switchTo.window(client.getWindowHandles.asScala.toList(windowCount))
                    client.close
                    client.switchTo.window(window)
                }
            }
        }
        else {
            if (multiBrowser && multiBrowserMultiWindow && client.getWindowHandles.asScala.size > 2) {
                var counter = 0

                client.getWindowHandles.asScala foreach { aWindow =>
                    counter += 1

                    if (counter > 2) {
                        Logger.trace("Closing excess windows", Logger.SUPER_VERBOSE_INTENSITY)
                        client.switchTo.window(aWindow)
                        client.close
                    }
                }
            }
        }
    }
}

/**
  * Create the WebConfig object for JBrowser to allow configuration of the browser to perform optimally
  */
object FirefoxWebConfig {
    var multiBrowser = (Configuration.config \ "downloader" \ "browser" \ "multiBrowser").as[Boolean]

    // Setup Xvfb display
    if ((Configuration.config \ "downloader" \ "browser" \ "headless").as[Boolean]) {
        Logger.info(s"Loading Xvfb instance for headless browser on display :${(Configuration.config \ "downloader" \ "browser" \ "display").as[Int]}")
        try {
            (Configuration.config \ "downloader" \ "browser" \ "displayCommand").as[String].run
        }
        catch {
            case e: Throwable => Logger.warn(s"Unable to run display command - may already be running", error = e)
        }

        // Allow Xvfb to start up
        Tools.sleep(5 seconds)
    }

    // Define a port for running on single browser mode
    val marionettePort = Tools.findFreePort

    val driverPath = {
        val path = (Configuration.config \ "downloader" \ "browser" \ "driverPath").as[String]
        if (!path.startsWith("/"))
            System.getProperty("user.dir") + "/" + path
        else if (path.startsWith("./"))
            System.getProperty("user.dir") + path.drop(1)
        else
            path
    }

    val logPath = {
        Logger.logfilePath + "/" + "browser.log"
    }

    val extensionsPath = {
        val path = (Configuration.config \ "downloader" \ "browser" \ "extensionsPath").as[String]
        if (!path.startsWith("/"))
            System.getProperty("user.dir") + "/" + path + "/"
        else if (path.startsWith("./"))
            System.getProperty("user.dir") + path.drop(1) + "/"
        else
            path + "/"
    }

    val defaultPrefsPath = {
        val path = (Configuration.config \ "downloader" \ "browser" \ "defaultPrefsPath").as[String]
        if (!path.startsWith("/"))
            System.getProperty("user.dir") + "/" + path + "/"
        else if (path.startsWith("./"))
            System.getProperty("user.dir") + path.drop(1) + "/"
        else
            path + "/"
    }

    System.setProperty("webdriver.gecko.driver", driverPath)

    val modifyHeadersExtension = new File(extensionsPath + "modify_headers.xpi")
    val fasterExtension = new File(extensionsPath + "fasterfox.xpi")

    def service: GeckoDriverService = {
        class Builder extends org.openqa.selenium.remote.service.DriverService.Builder[GeckoDriverService, Builder] {
            protected def findDefaultExecutable: File = new File(driverPath)

            protected def createArgs: ImmutableList[String] = {
                val webDriverLogLevel = (Configuration.config \ "logging" \ "browser").as[String] match {
                    case Logger.ERROR => "fatal"
                    case Logger.WARN => "warn"
                    case Logger.INFO => "info"
                    case Logger.DEBUG => "debug"
                    case Logger.TRACE => "trace"
                    case Logger.OFF => "fatal"
                    case Logger.ALL => "trace"
                    case "config" => "config"
                    case _ => "fatal"
                }

                val argsBuilder = ImmutableList.builder[String]
                argsBuilder.add("--port=%d".format(this.getPort))
                argsBuilder.add(s"--log=${webDriverLogLevel}")

                if (!multiBrowser) {
                    // If the browser is already up and running, connect to the existing instance
                    if (Tools.isPortInUse(FirefoxWebConfig.marionettePort)) {
                        argsBuilder.add(s"--connect-existing")
                    }

                    argsBuilder.add(s"--marionette-port=${FirefoxWebConfig.marionettePort}")
                }

                argsBuilder.add("-b")
                argsBuilder.add((Configuration.config \ "downloader" \ "browser" \ "binaryPath").as[String])

                if (this.getLogFile != null) argsBuilder.add("--log-file=\"%s\"".format(this.getLogFile.getAbsolutePath))

                val args = argsBuilder.build
                Logger.info(s"Setting args for geckodriver as follows: ${args}")
                args
            }

            protected def createDriverService(exe: File, port: Int, args: ImmutableList[String], environment: ImmutableMap[String, String]): GeckoDriverService = {
                try {
                    new GeckoDriverService(exe, port, args, environment)
                }
                catch {
                    case e: java.io.IOException => {
                        throw new WebDriverException(e)
                    }
                }
            }
        }

        if ((Configuration.config \ "downloader" \ "browser" \ "headless").as[Boolean]) {
           val gecko = new Builder()
               .usingAnyFreePort
               .withEnvironment(ImmutableMap.of("DISPLAY", s":${(Configuration.config \ "downloader" \ "browser" \ "display").as[Int]}",
                                                "MOZ_CRASHREPORTER_DISABLE", "1",
                                                "MOZ_CRASHREPORTER", "0"))
               .build

            gecko.start()
            gecko
        }
        else {
            val gecko = new Builder()
                .usingAnyFreePort
                .build

            gecko.start()
            gecko
        }
    }

    /**
      * Define the settings for the browser based on a combination of predefined requirements and form settings
      * within the configuration file
      *
      * @param browserIdentifier
      * @return
      */
    def settings(browserIdentifier: String): DesiredCapabilities = {
        try {
            var capabilities = DesiredCapabilities.firefox
            capabilities.setPlatform(org.openqa.selenium.Platform.ANY)

            capabilities.setCapability("marionette", true)
            capabilities.setCapability(CapabilityType.APPLICATION_NAME, browserIdentifier)
            capabilities.setCapability(CapabilityType.BROWSER_NAME, (Configuration.config \ "downloader" \ "browser" \ "type").as[String])
            capabilities.setCapability("acceptInsecureCerts", (Configuration.config \ "downloader" \ "browser" \ "sslTrustAll").as[Boolean])


            if ((Configuration.config \ "downloader" \ "browser" \ "headless").as[Boolean]) {
                new FirefoxOptions()
                    .addArguments(s"--display=:${(Configuration.config \ "downloader" \ "browser" \ "display").as[Int]}")
                    .setProfile(profile(browserIdentifier))
                    .addTo(capabilities)
            }
            else {
                new FirefoxOptions()
                    .setProfile(profile(browserIdentifier))
                    .addTo(capabilities)
            }
        }
        catch {
            case e: Throwable => {
                Logger.error(s"Error loading settings for Firefox browser: ${e.getMessage}", error = e)
                DesiredCapabilities.firefox
            }
        }
    }

    /**
      *
      * Speed reference: https://gist.github.com/amorgner/6746802
      *
      * @param browserIdentifier
      * @return
      */
    def profile(browserIdentifier: String): FirefoxProfile = {
        val proxyHost = Configuration.responderAddress
        val proxyPort = Downloader.proxy.proxy.getPort

        val profile = new FirefoxProfileEnhanced
        profile.addExtension(modifyHeadersExtension)

        if ((Configuration.config \ "downloader" \ "browser" \ "enableFasterExtension").as[Boolean])
            profile.addExtension(fasterExtension)

        // Refer: http://kb.mozillazine.org/Security_Policies
        // profile.setPreference("capability.policy.policynames", "strict")

        profile.setPreference("extensions.interposition.enabled", (Configuration.config \ "downloader" \ "browser" \ "enableDefaultExtensions").as[Boolean])
        profile.setPreference("extensions.pocket.enabled", (Configuration.config \ "downloader" \ "browser" \ "enableDefaultExtensions").as[Boolean])
        profile.setPreference("extensions.update.enabled", (Configuration.config \ "downloader" \ "browser" \ "enableDefaultExtensions").as[Boolean])
        profile.setPreference("extensions.webcompat-reporter.enabled", (Configuration.config \ "downloader" \ "browser" \ "enableDefaultExtensions").as[Boolean])
        profile.setPreference("extensions.webextensions.themes.enabled", (Configuration.config \ "downloader" \ "browser" \ "enableDefaultExtensions").as[Boolean])
        profile.setPreference("extensions.shield-recipe-client.enabled", (Configuration.config \ "downloader" \ "browser" \ "enableDefaultExtensions").as[Boolean])

        profile.setPreference("modifyheaders.headers.count", 1)
        profile.setPreference("modifyheaders.headers.action0", "Add")
        profile.setPreference("modifyheaders.headers.name0", "BrowserID")
        profile.setPreference("modifyheaders.headers.value0", browserIdentifier)
        profile.setPreference("modifyheaders.headers.enabled0", true)
        profile.setPreference("modifyheaders.config.active", true)
        profile.setPreference("modifyheaders.config.alwaysOn", true)

        profile.setPreference("javascript.enabled", (Configuration.config \ "downloader" \ "browser" \ "javascript").as[Boolean])

        profile.setPreference("browser.sessionhistory.max_entries", (Configuration.config \ "downloader" \ "browser" \ "historySize").as[Int])
        profile.setPreference("browser.sessionhistory.max_total_viewers", (Configuration.config \ "downloader" \ "browser" \ "historySize").as[Int])

        profile.setPreference("browser.cache.disk.enable", (Configuration.config \ "downloader" \ "browser" \ "cache").as[Boolean])
        profile.setPreference("browser.cache.disk.capacity", (Configuration.config \ "downloader" \ "browser" \ "cacheSize").as[Int])
        profile.setPreference("browser.cache.disk_cache_ssl", (Configuration.config \ "downloader" \ "browser" \ "cache").as[Boolean])

        profile.setPreference("network.http.pipelining", (Configuration.config \ "downloader" \ "browser" \ "pipeline").as[Boolean])
        profile.setPreference("network.http.proxy.pipelining", (Configuration.config \ "downloader" \ "browser" \ "pipeline").as[Boolean])
        profile.setPreference("network.http.pipelining.aggressive", (Configuration.config \ "downloader" \ "browser" \ "pipeline").as[Boolean])
        profile.setPreference("network.http.pipelining.ssl", (Configuration.config \ "downloader" \ "browser" \ "pipeline").as[Boolean])
        profile.setPreference("network.http.pipelining.maxrequests", (Configuration.config \ "downloader" \ "browser" \ "maxRequests").as[Int])
        profile.setPreference("network.http.max-connections", (Configuration.config \ "downloader" \ "browser" \ "maxConnections").as[Int])
        profile.setPreference("network.http.max-persistent-connections-per-proxy", (Configuration.config \ "downloader" \ "browser" \ "maxPersistentConnections").as[Int])
        profile.setPreference("network.http.max-persistent-connections-per-server", (Configuration.config \ "downloader" \ "browser" \ "maxPersistentConnections").as[Int])

        if (!(Configuration.config \ "downloader" \ "browser" \ "multiBrowser").as[Boolean]) {
            profile.setPreference("browser.link.open_newwindow", 3)
            profile.setPreference("browser.link.open_external", 3)
        }
        else {
            profile.setPreference("browser.link.open_newwindow", 1)
            profile.setPreference("browser.link.open_external", 1)
        }

        if ((Configuration.config \ "downloader" \ "browser" \ "blockAds").as[Boolean]) {
            profile.setPreference("dom.popup_allowed_events", "")
        }

        if ((Configuration.config \ "downloader" \ "browser" \ "blockImages").as[Boolean]) {
            profile.setPreference("permissions.default.image", 2)
            profile.setPreference("browser.chrome.favicons", false)
            profile.setPreference("browser.chrome.site_icons", false)
        }

        if ((Configuration.config \ "downloader" \ "browser" \ "blockVideo").as[Boolean]) {
            profile.setPreference("media.autoplay.enabled", false)
        }

        profile.setPreference("devtools.errorconsole.enabled", (Configuration.config \ "downloader" \ "browser" \ "enableDevTools").as[Boolean])
        profile.setPreference("devtools.debugger.enabled", (Configuration.config \ "downloader" \ "browser" \ "enableDevTools").as[Boolean])
        profile.setPreference("devtools.fontinspector.enabled", (Configuration.config \ "downloader" \ "browser" \ "enableDevTools").as[Boolean])
        profile.setPreference("devtools.inspector.enabled", (Configuration.config \ "downloader" \ "browser" \ "enableDevTools").as[Boolean])
        profile.setPreference("devtools.jsonview.enabled", (Configuration.config \ "downloader" \ "browser" \ "enableDevTools").as[Boolean])
        profile.setPreference("devtools.memory.enabled", (Configuration.config \ "downloader" \ "browser" \ "enableDevTools").as[Boolean])
        profile.setPreference("devtools.netmonitor.enabled", (Configuration.config \ "downloader" \ "browser" \ "enableDevTools").as[Boolean])
        profile.setPreference("devtools.performance.enabled", (Configuration.config \ "downloader" \ "browser" \ "enableDevTools").as[Boolean])
        profile.setPreference("devtools.responsive.html.enabled", (Configuration.config \ "downloader" \ "browser" \ "enableDevTools").as[Boolean])
        profile.setPreference("devtools.screenshot.audio.enabled", (Configuration.config \ "downloader" \ "browser" \ "enableDevTools").as[Boolean])
        profile.setPreference("devtools.webide.enabled", (Configuration.config \ "downloader" \ "browser" \ "enableDevTools").as[Boolean])
        profile.setPreference("devtools.toolbar.enabled", (Configuration.config \ "downloader" \ "browser" \ "enableDevTools").as[Boolean])
        profile.setPreference("devtools.styleeditor.enabled", (Configuration.config \ "downloader" \ "browser" \ "enableDevTools").as[Boolean])


        if ((Configuration.config \ "downloader" \ "browser" \ "quickRender").as[Boolean]) {
            profile.setPreference("javascript.options.jit.chrome", true)
            profile.setPreference("javascript.options.jit.content", true)
        }


        if ((Configuration.config \ "downloader" \ "browser" \ "enablePlugins").as[Boolean]) {
            profile.setPreference("plugin.state.flash", 2)
            profile.setPreference("plugin.state.java", 2)
            profile.setPreference("plugin.default.state", 2)
            profile.setPreference("plugin.defaultXpi.state", 2)
        }
        else {
            profile.setPreference("plugin.state.flash", 0)
            profile.setPreference("plugin.state.java", 0)
            profile.setPreference("plugin.default.state", 0)
            profile.setPreference("plugin.defaultXpi.state", 0)
        }

        profile.setPreference("webdriver.log.browser.file", "/dev/null")

        if ((Configuration.config \ "downloader" \ "proxy" \ "enable").as[Boolean]) {
            profile.setPreference("network.proxy.http", proxyHost)
            profile.setPreference("network.proxy.http_port", proxyPort)
            profile.setPreference("network.proxy.ssl", proxyHost)
            profile.setPreference("network.proxy.ssl_port", proxyPort)
            profile.setPreference("network.proxy.type", 1)
            profile.setPreference("network.proxy.no_proxies_on", "")
        }

        profile
    }

    class FirefoxProfileEnhanced extends FirefoxProfile {

        override def onlyOverrideThisIfYouKnowWhatYouAreDoing(): Reader = {
            try {
                new FileReader(new File(defaultPrefsPath))
            }
            catch {
                case e: Exception => {
                    Logger.error("Failed to override default preferences for Firefox profile", error = e)
                    null
                }
            }
        }
    }
}
