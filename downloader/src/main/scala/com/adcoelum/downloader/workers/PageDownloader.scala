package com.adcoelum.downloader.workers

import java.net.{URL, URLConnection}

import akka.actor.{Actor, ActorSelection}
import com.adcoelum.downloader.Downloader
import com.adcoelum.actors._
import com.adcoelum.client._
import com.adcoelum.client.firefox.FirefoxWebClient
import com.adcoelum.common._
import org.openqa.selenium.TimeoutException
import com.adcoelum.data.DatabaseAccessor
import com.adcoelum.error.{DownloadTimeoutException, PageNotFoundException, TooManyRequestsException}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.language.postfixOps


/**
  * The page downloader class is a complex class for handling page downloads as the final and key component of the crawler
  * This class performs some clever work around page downloading, button clicking and can even handle infinite scrolling pages
  * in concert with the proxy.  TODO: Breakout the varied components for more advanced handling
  */
class PageDownloader extends Actor {
	// Establish connections to local actors
	val downloadQueuer = context.actorSelection("/user/download_queue_router")
	val downloadRouter = context.actorSelection("/user/download_router")
	val activityMonitor = context.actorSelection("/user/activity_monitor")

	// Connect to the data collection
	val commandControlData = DatabaseAccessor.collection("command_control")

	// Set the max clicks/scrolls for a browser
	val maximumSuccessClicks = (Configuration.config \ "downloader" \ "maxSuccessClicks").as[Int]
	val maximumClickAttempts = (Configuration.config \ "downloader" \ "maxClickAttempts").as[Int]
	val maximumScrolls = (Configuration.config \ "downloader" \ "maxScrolls").as[Int]


	// Establish connections to remote actors - treat as a method call
	var indexerSystems: List[Tuple3[String, String, Int]] = List.empty[Tuple3[String, String, Int]]

	// Get a list of current available indexer systems
	indexerSystemsList(setIndexerSystems)

	// Define link chain depth
	val linkChainDepth = (Configuration.config \ "crawler" \ "linkChainDepth").as[Int]

	// Define matchers
	val onClickMatcher = "['\"](.*)['\"]".r

	// Define search conditions
	val buttonXPathSearch = "//*[contains(concat(' ',normalize-space(@class),' '),' button ') and not(self::a) and not(self::input) and not(ancestor::form) and not(ancestor::a)] | " +
							"//*[contains(@class,'button') and not(self::a) and not(self::input) and not(ancestor::form) and not(ancestor::a)] | " +
							"//button[not(ancestor::form) and not(ancestor::a)] | " +
							"//*[@onclick and not(self::a) and not(self::input) and not(ancestor::form) and not(ancestor::a)]"

	// Define hashing method
	val hashMethod = "SHA-1"
	val digest = java.security.MessageDigest.getInstance(hashMethod)

	// Define identifiers
	val stringType = "UTF-8"
	val stringFormatter = "%02x"
	val anchorReference = "#"
	val onClickIdentifier = "onclick"
	val windowIdentifier = "window"

	//  Obtain the downloader client
	val client: HttpClient = new FirefoxWebClient

	// Set the state of this actor
	var state: State = Idle

	// Handle a regular check for when the state of the downloader is idle
	val idleFrequency = (Configuration.config \ "downloader" \ "idleCheckFrequency").as[Int] seconds
	val idleTimeout = (Configuration.config \ "downloader" \ "idleTimeout").as[Int] seconds
	var lastWorkAsk = System.currentTimeMillis

	val doScrolls = (Configuration.config \ "downloader" \ "doScrolls").as[Boolean]
	val doClicks = (Configuration.config \ "downloader" \ "doClicks").as[Boolean]

	/**
	  * Establish the types of messages that will be handled by the actor
	  *
	  * @return
	  */
	def receive = {
		// Handle download page message
    	case DownloadPage(crawlMethod, visits, urlChain, domain, hash) => {
		    try {
				// Handle with and without existing hash info
				if (hash == "")
					downloadPage(crawlMethod, visits, urlChain)
				else
					downloadPage(crawlMethod, visits, urlChain, Some(hash))

				downloadRouter ! Notify()
            }
      	    catch {
      	        case e: Throwable => Logger.error(s"Issue processing page download ${e.getMessage}", error = e)
      	    }
    	}

		case UpdateRemoteSystems() => {
			indexerSystemsList(setIndexerSystems)
		}

		case IdleCheck() => {
			Logger.trace(s"Performing an idle check for downloader with browser ${client.id}", Logger.ULTRA_VERBOSE_INTENSITY)

			if (state == Idle && System.currentTimeMillis - lastWorkAsk >= idleFrequency.toMillis) {
				Logger.trace(s"Directing to ask for work from idle check from downloader with browser ${client.id}", Logger.VERBOSE_INTENSITY)
				askForWork
			}
		}
  	}

	/**
	  * Key method in the class for handling page downloads
	  *
	  * @param urlChain
	  * @param originalHash
	  */
  	def downloadPage(crawlMethod: Int, visits: Int, urlChain: List[URL], originalHash: Option[String] = None) {
		// Switch state to active
		switchToActive

		// Establish statistical values
		val statistics = mutable.HashMap.empty[String, Any]

		// Establish current time
		val startTime = System.currentTimeMillis

		// Confirm page downloading and processing complete or skipped
		var downloadingComplete = false
		var processingComplete = false

		// Obtain current url from url chain
		val url: URL = urlChain.head

		// Tell proxy the current URL chain for this download
		Downloader.proxy.setURLChain(client.id, urlChain)

		try {
			Logger.debug(s"Downloading page: ${url.toString}")
			activityMonitor ! Statistic("page", Map(
				s"browsers.${client.id}.last_page" -> url.toString,
				s"browsers.${client.id}.action" -> "downloading")
			)

			// Obtain the page and store as a raw format with corrections to errors in the structure
			val rawPage: Page = client.get(url)
			val pageLoadTime: Long = System.currentTimeMillis - startTime
			Logger.debug(s"Downloaded page in ${pageLoadTime}ms: ${url.toString}")
			activityMonitor ! Statistic("page", Map(
				s"browsers.${client.id}.last_download_time" -> System.currentTimeMillis,
				"download_time" -> pageLoadTime,
				s"browsers.${client.id}.action" -> "downloaded")
			)

			// Obtain the status code for the page to determine what to do next
			val status = client.status

			// Perform error handling and adjustment throttling
			status match {
				case 429 | 503 | 504 => {
					throw new TooManyRequestsException(status)
				}
				case 404 => {
					ScoringEngine.resetScore(Tools.urlToIdentifier(url), Configuration.IGNORE, visits)
					getPageClassifyRouter ! ClassifyPage(url, MiscellaneousCategory())
					downloadingComplete = true
					throw new PageNotFoundException(status)
				}
				case _ => {
					downloadingComplete = true
				}
			}

			// Structure the html of the page downloaded
			val xmlPage: String = Tools.structureHtml(rawPage.getXml)
			activityMonitor ! Statistic("page", Map(
				"download_size" -> xmlPage.length,
				s"browsers.${client.id}.action" -> "parsing")
			)

			// Perform further error handling and adjustment throttling
			if (xmlPage.contains("Gateway Timeout"))
				throw new TooManyRequestsException(504)

			Logger.trace(s"Finished parsing page length ${xmlPage.length} after ${(System.currentTimeMillis - startTime) / 1000} seconds: ${url.toString}")

			// Obtain the final url following redirects and url re-writes - ensure still on the same site - no redirects
			val finalUrl = rawPage.getResponseUrl
			if (finalUrl.getHost == urlChain.last.getHost) {
				activityMonitor ! Statistic("page", Map(
					s"browsers.${client.id}.action" -> "processing")
				)

				// Reset the score of the page to zero
				ScoringEngine.resetScore(Tools.urlToIdentifier(url), crawlMethod, visits)
				ScoringEngine.resetScore(Tools.urlToIdentifier(finalUrl), crawlMethod, visits)
				Logger.debug(s"Score reset to zero for ${finalUrl.toString}", Logger.VERBOSE_INTENSITY)

				// Amend url chain to reflect final url
				val finalUrlChain: List[URL] = finalUrl :: urlChain.drop(1)

				// Determine the hash for the page
				val hash = digest.digest(xmlPage.getBytes(stringType)).map(stringFormatter.format(_)).mkString
				Logger.trace(s"Hash ${hash} generated for page: ${url.toString}", Logger.MEGA_VERBOSE_INTENSITY)

				// Evaluate and pull data for page
				Logger.trace(s"Evaluating the page, storing in cache and processing data and links: ${url.toString}")

				status match {
					case s if 200 until 399 contains s => {
						// Determine whether to proceed further if the hash is different
						if (originalHash.isEmpty || originalHash.get != hash) {
							// Update Hash for url
							getCrawlFrontierRouter(finalUrlChain) ! UpdateUrlHash(crawlMethod, visits + 1, status, finalUrlChain, hash)

							// Perform actions for the downloaded page
							if (finalUrl.getHost == urlChain.last.getHost) {
								// Notify page visit to finalUrl
								if (finalUrl != url) {
									getCrawlFrontierRouter(finalUrlChain) ! NotifyUrlVisit(crawlMethod, visits + 1, status, finalUrlChain)
									getCrawlFrontierRouter(finalUrlChain) ! UpdateUrlHash(crawlMethod, visits + 1, status, finalUrlChain, hash)
								}

								val compressedXmlPage = Tools.stringCompress(xmlPage)
								getPageClassifyRouter ! ProcessPage(crawlMethod, visits, finalUrlChain, compressedXmlPage)
								getLinkFollowRouter(finalUrlChain) ! ProcessLinks(crawlMethod, finalUrlChain, compressedXmlPage)
							}
						}
						else
							Logger.trace(s"Hash ${hash} has matched original ${originalHash} meaning no change to page - no further processing performed on ${url.toString}", Logger.MEGA_VERBOSE_INTENSITY)
					}
					case _ => {
						// Perform actions for the downloaded page
						if (finalUrl.getHost == urlChain.last.getHost) {
							// Notify page visit to finalUrl
							if (finalUrl != url)
								getCrawlFrontierRouter(finalUrlChain) ! NotifyUrlVisit(crawlMethod, visits + 1, status, finalUrlChain)
						}
					}
				}

				Logger.trace(s"Finished pulling data from page after ${(System.currentTimeMillis - startTime) / 1000} seconds: ${finalUrl.toString}")


				// Attempting to handle scrolling to find out if the page performs infinite scrolling
				if (doScrolls) {
					rawPage.refreshPage
					handleScrolling(rawPage, crawlMethod, visits, finalUrlChain, startTime)
				}

				// Attempting to handle button clicks to find pages only available from clicks and not links
				if (doClicks) {
					rawPage.refreshPage
					handleButtonClicks(rawPage, crawlMethod, visits, finalUrlChain, startTime)
				}

				processingComplete = true
			}
			else
				Logger.trace(s"Page evaluation skipped due to host for final url ${finalUrl.getHost} not matching original url ${urlChain.last.getHost}")
		}
		// Handle specific error cases
		catch {
			case e: java.net.ConnectException => {
				Logger.warn(s"Page check failed due to inability to connect - adjusting throttle and trying again: $url", error = e)

				// Adjust Throttle for download router
				adjustThrottling(url.getHost, 1)

				// Visit the page and download content again
				downloadQueuer ! Queue(DownloadPage(crawlMethod, visits, urlChain, url.getHost, originalHash.getOrElse("")))
			}
			case e: java.net.SocketTimeoutException => {
				Logger.warn(s"Page download timed out (socket timeout) - time ${(System.currentTimeMillis - startTime) / 1000} seconds - adjusting throttle and trying again: $url", error = e)

				// Adjust Throttle for download router
				adjustThrottling(url.getHost, 1)

				// Visit the page and download content again
				// downloadQueuer.tell(Queue(DownloadPage(crawlMethod, urlChain, originalHash.getOrElse(""))), sender)
				downloadQueuer ! Queue(DownloadPage(crawlMethod, visits, urlChain, url.getHost, originalHash.getOrElse("")))

				// Reduce page download rate
				// downloadQueuer ! ReduceRate(1 second)
			}
			case e: TimeoutException => {
				Logger.warn(s"Page download timed out (general timeout) - time ${(System.currentTimeMillis - startTime) / 1000} seconds - resetting client, adjusting throttle and trying again: $url", error = e)

				// Reset the browser
				client.reset

				// Adjust Throttle for download router
				adjustThrottling(url.getHost, 1)

				// Visit the page and download content again
				downloadQueuer ! Queue(DownloadPage(crawlMethod, visits, urlChain, url.getHost, originalHash.getOrElse("")))
			}
			case e: org.openqa.selenium.NoSuchElementException => {
				Logger.warn(s"Unable to find element while getting page: $url", error = e)
			}
			case e: org.openqa.selenium.SessionNotCreatedException => {
				Logger.warn(s"Client unable to create a session - trying again and resetting client: ${e.getMessage}", error = e)

				// Reset the browser
				client.reset

				// Visit the page and download content again
				downloadQueuer ! Queue(DownloadPage(crawlMethod, visits, urlChain, url.getHost, originalHash.getOrElse("")))
			}
			case e: org.openqa.selenium.WebDriverException => {
				Logger.warn(s"Client experienced error - trying again and resetting client: ${e.getMessage}", error = e)

				// Reset the browser
				client.reset

				// Visit the page and download content again
				downloadQueuer ! Queue(DownloadPage(crawlMethod, visits, urlChain, url.getHost, originalHash.getOrElse("")))
			}
			case e: TooManyRequestsException => {
				Logger.warn(s"Too many requests attempted based on status code ${e.statusCode} - adjusting throttle and trying again: ${e.getMessage}", error = e)

				// Adjust Throttle for download router
				adjustThrottling(url.getHost, 1)

				// Visit the page and download content again
				downloadQueuer ! Queue(DownloadPage(crawlMethod, visits, urlChain, url.getHost, originalHash.getOrElse("")))
			}
			case e: PageNotFoundException => {
				Logger.warn(s"Page no longer exists based on status code ${e.statusCode}: ${e.getMessage}", error = e)
			}
			case e: DownloadTimeoutException => {
				Logger.warn(s"Page download timed out (get timeout) - time ${(System.currentTimeMillis - startTime) / 1000} seconds - adjusting throttle and trying again: $url", error = e)

				// Adjust Throttle for download router
				adjustThrottling(url.getHost, 1)

				// Visit the page and download content again
				downloadQueuer ! Queue(DownloadPage(crawlMethod, visits, urlChain, url.getHost, originalHash.getOrElse("")))
			}
			case e: Throwable => {
				Logger.error(s"General error while downloading page: ${url.toString}: ${e.getMessage}", error = e)
			}
		}
		// Perform some final actions
		finally {
			// Handle testing case for pauses
			if (Configuration.pause) {
				Logger.trace("Pausing processing...")
				Tools.sleep(10 minutes)
			}

			val finishTime = System.currentTimeMillis - startTime

			// Notify Page Downloaded
			if (processingComplete) {
				Logger.debug(s"Finished processing page successfully after ${finishTime / 1000} seconds on browser ${client.id} seconds: ${url.toString}")

				activityMonitor ! Statistic("page", Map(
					"total_page_time" -> finishTime,
					s"browsers.${client.id}.action" -> "completed")
				)

				getCrawlFrontierRouter(urlChain) ! PageDownloaded(crawlMethod, urlChain)
			}
			else if (downloadingComplete) {
				Logger.warn(s"Finished downloading page but not all processing after ${finishTime / 1000} seconds on browser ${client.id} with errors: ${url.toString}", error = new Exception("Page processing did not complete"))
				activityMonitor ! Statistic("page", Map(
					"total_page_time" -> finishTime,
					s"browsers.${client.id}.action" -> "completed")
				)

				getCrawlFrontierRouter(urlChain) ! PageDownloaded(crawlMethod, urlChain)
			}
			else {
				Logger.warn(s"Page download failed ${finishTime / 1000} seconds on browser ${client.id} with errors: ${url.toString}", error = new Exception("Page download did not complete"))
				activityMonitor ! Statistic("page", Map(
					"total_page_time" -> finishTime,
					s"browsers.${client.id}.action" -> "failed")
				)
			}

			// Close the client - depending on the implementation
			client.close

			// Set state to idle and make a request to give a download message in the event of a pull model
			switchToIdle
		}
  	}

	/**
	  * Method for handling scrolling case where page is capable of infinite scrolling
	  *
	  * @param page
	  * @param urlChain
	  */
	def handleScrolling(page: Page, crawlMethod: Int, visits: Int, urlChain: List[URL], startTime: Long) = {
		// Pull url from url chain
		val url = urlChain.head

		Logger.trace(s"Attempting scrolling to determine if an infinite scrolling page: ${url.toString}")
		activityMonitor ! Statistic("page", Map(
			s"browsers.${client.id}.action" -> "scrolling")
		)

		// Handle situations where page changes based on scrolling to the bottom
		val heightScript = "return Math.max(document.documentElement.clientHeight, document.body.scrollHeight, " +
			"document.documentElement.scrollHeight, document.body.offsetHeight, document.documentElement.offsetHeight)"
		val positionScript = "return Math.max(document.body.scrollTop, window.pageYOffset, document.documentElement.scrollTop)"

		// Initialise variables to perform scroll testing
		var rawScrollPage: Page = page
		var currentHeight: Double = 0.0
		var currentPosition: Double = 0.0
		var previousHeight = currentHeight
		var previousPosition = currentPosition
		var scrolls = 0

		// Begin scrolling attempts
		do {
			// Establish baseline positions for scrolling
			currentHeight = rawScrollPage.executeScriptForResult(heightScript).toDouble
			currentPosition = rawScrollPage.executeScriptForResult(positionScript).toDouble
			previousHeight = currentHeight
			previousPosition = currentPosition

			// Perform a scroll attempt
			Logger.trace(s"Attempting scroll from ${currentPosition} to position ${currentHeight}: ${url.toString}")
			rawScrollPage = rawScrollPage.executeScriptForPage("window.scrollTo(0,"+currentHeight+")")
			scrolls += 1

			// Perform a wait to allow any ajax calls to take place
			client.waitForActions

			// Determine position of browser following scroll attempt
			currentHeight = rawScrollPage.executeScriptForResult(heightScript).toDouble
			currentPosition = rawScrollPage.executeScriptForResult(positionScript).toDouble
			Logger.trace(s"After ${scrolls} scrolls, new scroll position ${currentPosition} and height ${currentHeight}: ${url.toString}")

			// Where the page has provided new content, obtain the new content
			if (scrolls >= 2 && currentPosition != 0.0 && currentPosition != currentHeight) {
				val scrollXmlPage = Tools.structureHtml(rawScrollPage.getXml)
				val scrollCompressedXmlPage = Tools.stringCompress(scrollXmlPage)

				Logger.trace(s"Using updated page after ${scrolls} scrolls: ${url.toString}")
				getLinkFollowRouter(urlChain) ! ProcessLinks(crawlMethod, urlChain, scrollCompressedXmlPage)
			}
		}
		// Perform check to see if the page has provided new content based on adjusted height
		while (previousHeight < currentHeight && maximumScrolls >= 0 && scrolls <= maximumScrolls)

		Logger.trace(s"Finished scrolling after ${scrolls} scrolls after ${(System.currentTimeMillis - startTime) / 1000} seconds: ${url.toString}")
	}

	/**
	  * Method for handling button click case to access hidden Urls due to buttons being used
	  *
	  * @param page
	  * @param urlChain
	  */
	def handleButtonClicks(page: Page, crawlMethod: Int, visits: Int, urlChain: List[URL], startTime: Long) = {
		// Obtain url from url chain
		val url = urlChain.head

		// Find all elements with button class and try clicking
		Logger.trace(s"Examining ${page.searchXPath(buttonXPathSearch).size} buttons on: ${url.toString}")
		activityMonitor ! Statistic("page", Map(
			s"browsers.${client.id}.action" -> "clicking")
		)

		// Establish base data for handling clicks
		var attemptedClicks = 0
		var clicks = 0
		var buttonLinks: ListBuffer[URL] = ListBuffer.empty[URL]
		var buttonFilters: mutable.HashMap[String, Int] = mutable.HashMap.empty[String, Int]

		// Prevent GETs while clicking buttons
		// client.disableGET
		var buttons = page.searchXPath(buttonXPathSearch)

		// Iterate through each button
		for (counter <- 0 to buttons.size; if counter < buttons.size && counter < maximumClickAttempts) {
			try {
				// Capture button element for manipulation ensuring the button list is refreshed in case of page changes
				val button: Element = buttons(counter)
				val buttonRawHtml: String = button.rawHtml

				// Case where button has onClick identifier contains window instruction
				if (button.hasAttribute(onClickIdentifier) && button.getAttribute(onClickIdentifier).contains(windowIdentifier)) {
					// Obtain Urls to follow in button
					val results = onClickMatcher findAllMatchIn button.getAttribute(onClickIdentifier)

					results foreach { result =>
						val buttonClickText = result.subgroups.head
						try {
							// Add Url for processing at end as a URL rather than a button click
							val buttonClickUrl: URL = new URL(buttonClickText)
							val urlIdentifier = Tools.urlToIdentifier(url)
							val buttonUrlIdentifier = Tools.urlToIdentifier(buttonClickUrl)

							if (buttonUrlIdentifier != urlIdentifier)
								buttonLinks += buttonClickUrl
						}
						catch {
							case e: Throwable => Logger.warn(s"URL not valid for button: ${buttonClickText}", error = e)
						}
					}
				}
				// Handle true button case so long as maximum clicks is not exceeded
				else if (maximumSuccessClicks >= 0 && clicks <= maximumSuccessClicks) {
					if (buttonFilters.contains(buttonRawHtml)) {
						Logger.trace(s"Button ${counter + 1} skipped as shares same appearance as non-actionable button on url: ${url.toString}", Logger.VERBOSE_INTENSITY)
					}
					else {
						// Perform click and capture resulting page and url
						val rawButtonPage = button.doClick
						val buttonUrl = rawButtonPage.getResponseUrl
						attemptedClicks += 1
						Logger.trace(s"Button ${counter + 1} clicked on page: ${url.toString} with resulting url: ${url.toString}", Logger.VERBOSE_INTENSITY)

						// Obtain status code for the page following button click
						val status = client.status

						status match {
							case s if 200 until 399 contains s => {
								// Perform actions for the downloaded page if it is indeed different
								if (!buttonUrl.toString.contains(anchorReference) && buttonUrl != url && buttonUrl.getHost == urlChain.last.getHost) {
									// Add button url to chain, ensuring not longer than defined depth
									val buttonUrlChain = buttonUrl :: {
										if (urlChain.size >= linkChainDepth)
											urlChain.dropRight(1)
										else
											urlChain
									}

									// Evaluate the new page and prepare for analysis
									val buttonXmlPage = Tools.structureHtml(rawButtonPage.getXml)
									val buttonCompressedXmlPage = Tools.stringCompress(buttonXmlPage)

									Logger.trace(s"Using button url with string len ${buttonXmlPage.length}: ${buttonUrl.toString}")

									// Classify page and follow links and increment click counter
									getPageClassifyRouter ! ProcessPage(crawlMethod, visits, buttonUrlChain, buttonCompressedXmlPage)
									getLinkFollowRouter(buttonUrlChain) ! ProcessLinks(crawlMethod, buttonUrlChain, buttonCompressedXmlPage)
									clicks += 1
								}
							}
							case _ => {
								// Do Nothing
							}
						}

						// Determine whether the page has changed its path or query string from the click action
						if (buttonUrl.toString != url.toString) {
							// If page has changed path, go back and refresh
							client.back
							page.refreshPage
							buttons = page.searchXPath(buttonXPathSearch)
						}
						else
							buttonFilters += (buttonRawHtml -> 1)
					}
				}
			}
			catch {
				case e: org.openqa.selenium.StaleElementReferenceException => {
					Logger.warn(s"Stale element while clicking - skipping processing of button ${counter + 1} on page ${url.toString}: ${e.getMessage}", error = e)
				}
			}
		}

		Logger.trace(s"Passing links found in buttons for processing on page: ${url.toString}")
		getLinkFollowRouter(urlChain) ! ProcessLinksList(crawlMethod, urlChain, buttonLinks.toList)

		// client.enableGET

		Logger.trace(s"Finished clicking ${attemptedClicks} buttons with ${clicks} actionable clicks after ${(System.currentTimeMillis - startTime) / 1000} seconds: ${url.toString}")
	}


	// Callback function to perform the set
	def setIndexerSystems(list: List[Tuple3[String, String, Int]]) = {
		indexerSystems = list
	}


	def getPageClassifyRouter: ActorSelection = {
		if (indexerSystems.size == 0) {
			Logger.warn("No indexers available to set a router", error = new Exception("No indexers available"))
			context.actorSelection("")
		}
		else {
			val rand = scala.util.Random
			val chosen = {
				if (indexerSystems.size > 1)
					rand.nextInt(indexerSystems.size)
				else
					0
			}

			val selectionString = s"akka.tcp://${indexerSystems(chosen)._1}@${indexerSystems(chosen)._2}:${indexerSystems(chosen)._3}/user/page_classify_router"
			Logger.trace(s"Page Classify Router used: $selectionString", Logger.MEGA_VERBOSE_INTENSITY)
			context.actorSelection(selectionString)
		}
	}

	def indexerSystemsList(callback: Function1[List[Tuple3[String, String, Int]], Unit]) = {
		try {
			val query = Map.empty[String, Any]
			val projection = Map("host" -> 1, "indexers" -> 1)

			// Return the results based on the search
			def action(results: List[Map[String, Any]]) = {
				var indexers: ListBuffer[Tuple3[String, String, Int]] = ListBuffer.empty[Tuple3[String, String, Int]]

				results foreach { result =>
					if (result.contains("host") && result.contains("indexers")) {
						val host = result("host").asInstanceOf[String]

						result("indexers").asInstanceOf[Map[String, Map[String, Any]]] foreach { indexMap =>
							val name = indexMap._1
							val port = indexMap._2.asInstanceOf[Map[String, Any]].getOrElse("systemPort", 0).asInstanceOf[Int]
							indexers += new Tuple3(name, host, port)
						}
					}
				}

				callback(indexers.toList)
			}

			commandControlData.read(query, projection, action)
		}
		catch {
			case e: Throwable => Logger.warn(s"Obtain indexer map failed: ${e.getMessage}", error = e)
		}
	}


	def switchToIdle = {
		state = Idle
		activityMonitor ! Statistic("page", Map(s"browsers.${client.id}.state" -> "idle"))
		askForWork
	}

	def switchToActive = {
		state = Active
		activityMonitor ! Statistic("page", Map(s"browsers.${client.id}.state" -> "active"))
	}


	def askForWork = {
		if((Configuration.config \ "downloader" \ "method").as[String] == "pull") {
			// Get the highest ranked commander and send it a message to its seeder
			val commander = highestRankedCommander
			val selectionString = s"akka.tcp://${commander._2}@${commander._1}:${commander._3}/user/seeder"
			val seeder = context.actorSelection(selectionString)

			Logger.trace(s"Preparing to ask from seeder ${selectionString} from Downloader with browser ${client.id}", Logger.VERBOSE_INTENSITY)
			seeder ! Ask("download")
			downloadQueuer ! Notify()
			lastWorkAsk = System.currentTimeMillis
		}
	}

	def adjustThrottling(domain: String, direction: Int) = {
		if((Configuration.config \ "downloader" \ "method").as[String] == "pull") {
			// Get the highest ranked commander and send it a message to its seeder
			val commander = highestRankedCommander
			val selectionString = s"akka.tcp://${commander._2}@${commander._1}:${commander._3}/user/seeder"
			val seeder = context.actorSelection(selectionString)

			Logger.trace(s"Sending message to adjust throttling to seeder ${selectionString} in direction ${direction}", Logger.ULTRA_VERBOSE_INTENSITY)
			seeder ! AdjustThrottle(direction, domain)
		}
	}


	def highestRankedCommander: Tuple3[String, String, Int] = {
		var commanders: Map[String, Tuple4[String, String, Int, Int]] = Map.empty
		commandersMap((results) => {commanders = results})

		var highRank = -1
		var highest = new Tuple3[String, String, Int]("", "", 0)

		commanders.values foreach { eachCommander =>
			val commanderRank = eachCommander._4
			if (highRank == -1 || commanderRank < highRank) {
				highRank = commanderRank
				highest = (eachCommander._1, eachCommander._2, eachCommander._3)
			}
		}

		highest
	}


	def commandersMap(callback: Function1[Map[String, Tuple4[String, String, Int, Int]], Unit]) = {
		try {
			val query = Map.empty[String, Any]
			val projection = Map("commander" -> 1, "host" -> 1, "seederName" -> 1, "seederPort" -> 1, "rank" -> 1)

			def action(results: List[Map[String, Any]]) = {
				val map: mutable.HashMap[String, Tuple4[String, String, Int, Int]] = mutable.HashMap.empty[String, Tuple4[String, String, Int, Int]]

				if (results.nonEmpty) {
					results foreach { result =>
						val identifier = result("commander").asInstanceOf[String]

						val host = result.getOrElse("host", "").asInstanceOf[String]
						val seederName = result.getOrElse("seederName", "").asInstanceOf[String]
						val seederPort = result.getOrElse("seederPort", 0).asInstanceOf[Int]
						val rank = result.getOrElse("rank", -1).asInstanceOf[Int]

						map += (identifier -> (host, seederName, seederPort, rank))
					}
				}

				callback(map.toMap)
			}

			commandControlData.read(query, projection, action)
		}
		catch {
			case e: Throwable => Logger.warn(s"Obtain command and control map failed: ${e.getMessage}", error = e)
		}
	}

	/*
	def getCrawlFrontierRouter: ActorSelection = {
		val address = sender.path.address
		val system: String = address.system
		val host: String = address.host.getOrElse("")
		val port: Int = address.port.getOrElse(0)

		val selectionString = s"akka.tcp://${system}@${host}:${port}/user/crawl_frontier_router"
		Logger.trace(s"Crawl List Router used: $selectionString", Logger.ULTRA_VERBOSE_INTENSITY)
		context.actorSelection(selectionString)
	}


	def getLinkFollowRouter: ActorSelection = {
		val address = sender.path.address
		val system: String = address.system
		val host: String = address.host.getOrElse("")
		val port: Int = address.port.getOrElse(0)

		val selectionString = s"akka.tcp://${system}@${host}:${port}/user/link_follow_router"
		Logger.trace(s"Link Follow Router used: $selectionString", Logger.ULTRA_VERBOSE_INTENSITY)
		context.actorSelection(selectionString)
	}
	*/


	def getCrawlFrontierRouter(urlChain: List[URL]): ActorSelection = {
		var crawlerSystems = List.empty[Tuple3[String, String, Int]]
		crawlerSystemsList((crawlers) => {crawlerSystems = crawlers})

		var selectionString = ""
		var found = false

		val name = urlChain.last.getHost.replace(".", "_") + "_crawler"

		crawlerSystems foreach { crawlerSystem =>
			if (!found && crawlerSystem._1 == name) {
				selectionString = s"akka.tcp://${crawlerSystem._1}@${crawlerSystem._2}:${crawlerSystem._3}/user/crawl_frontier_router"
				Logger.trace(s"Crawl Frontier Router used: $selectionString", Logger.ULTRA_VERBOSE_INTENSITY)
				found = true
			}
		}

		if (selectionString == "") {
			Logger.warn(s"No matching crawler for ${name} available to set a router", error = new Exception(s"No crawlers available with name ${name}"))
			context.actorSelection("")
		}
		else
			context.actorSelection(selectionString)
	}

	def getLinkFollowRouter(urlChain: List[URL]): ActorSelection = {
		var crawlerSystems = List.empty[Tuple3[String, String, Int]]
		crawlerSystemsList((crawlers) => {crawlerSystems = crawlers})

		var selectionString = ""
		var found = false

		val name = urlChain.last.getHost.replace(".", "_") + "_crawler"

		crawlerSystems foreach { crawlerSystem =>
			if (!found && crawlerSystem._1 == name) {
				selectionString = s"akka.tcp://${crawlerSystem._1}@${crawlerSystem._2}:${crawlerSystem._3}/user/link_follow_router"
				Logger.trace(s"Link Follow Router used: $selectionString", Logger.ULTRA_VERBOSE_INTENSITY)
				found = true
			}
		}


		if (selectionString == "") {
			Logger.warn(s"No matching crawler for ${name} available to set a router", error = new Exception(s"No crawlers available with name ${name}"))
			context.actorSelection("")
		}
		else
			context.actorSelection(selectionString)
	}

	def crawlerSystemsList(callback: Function1[List[Tuple3[String, String, Int]], Unit]) = {
		try {
			val query = Map.empty[String, Any]
			val projection = Map("host" -> 1, "crawlers" -> 1)

			// Return the results based on the search
			def action(results: List[Map[String, Any]]) = {
				var crawlers: ListBuffer[Tuple3[String, String, Int]] = ListBuffer.empty[Tuple3[String, String, Int]]

				results foreach { result =>
					if (result.contains("host") && result.contains("crawlers")) {
						val host = result("host").asInstanceOf[String]

						result("crawlers").asInstanceOf[Map[String, Map[String, Any]]] foreach { indexMap =>
							val name = indexMap._1
							val port = indexMap._2.asInstanceOf[Map[String, Any]].getOrElse("systemPort", 0).asInstanceOf[Int]
							crawlers += new Tuple3(name, host, port)
						}
					}
				}

				callback(crawlers.toList)
			}

			commandControlData.read(query, projection, action)
		}
		catch {
			case e: Throwable => Logger.warn(s"Obtain indexer map failed: ${e.getMessage}", error = e)
		}
	}
}
