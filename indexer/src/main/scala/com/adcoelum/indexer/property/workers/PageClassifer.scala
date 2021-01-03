package com.adcoelum.indexer.property.workers

import java.nio.file.{Files, Paths, StandardOpenOption}

import akka.actor.Actor
import com.adcoelum.common._
import com.adcoelum.actors._
import com.adcoelum.data.DatabaseAccessor
import com.adcoelum.indexer.property.analytics.{Processing, TrainedPropertyClassifier}


/**
  * The page classifier class plays the important interface role for deciding whether pages will be handled by
  * the identifier, analysed and stored
  * TODO: Build this class based on an AI classifier
  */
class PageClassifier extends Actor {
	// Establish connections to local actors
	val rawDataPointProcessRouter = context.actorSelection("/user/raw_data_point_process_router")

	// Determine whether in training mode
	val trainingMode = (Configuration.config \ "indexer" \ "training" \ "mode").as[String]

	// Establish cases for where identification is a property
	val propertyTrainingIdentifier = (Configuration.config \ "indexer" \ "training" \ "classes" \ "property").as[String].r

	// Setup categoriser for classifying based on the established model
	val categoriser = new TrainedPropertyClassifier

	val cache = DatabaseAccessor.cache

	val trainingOutput = {
		val path = (Configuration.config \ "indexer" \ "training" \ "output").as[String]
		if (!path.startsWith("/"))
			System.getProperty("user.dir") + "/" + path
		else if (path.startsWith("./"))
			System.getProperty("user.dir") + path.drop(1)
		else
			path
	}

	/**
	  * Establish the types of messages that will be handled by the actor
	  *
	  * @return
	  */
	def receive = {
		// Process page and determine categorisation
    	case ProcessPage(crawlMethod, visits, urlChain, content) => {
			// Obtain url from url chain
			val url = urlChain.head

			if (trainingMode == "train") {

				Logger.trace(s"Training model to classify page: ${url.toString}")

                if (propertyTrainingIdentifier.findFirstIn(url.toString).isDefined) {
                    Logger.trace(s"Property page identified: ${url.toString}")
                    rawDataPointProcessRouter ! ProcessToClassify(crawlMethod, urlChain, content, PropertyCategory())
                }
                else {
                    Logger.trace(s"Miscellaneous page identified: ${url.toString}")
                    rawDataPointProcessRouter ! ProcessToClassify(crawlMethod, urlChain, content, MiscellaneousCategory())
                }
			}
			else {
				Logger.trace(s"Classifying page: ${url.toString}")

				// Establish base content for analysis such as core text, title and metadata
				val page = Tools.htmlToXml(Tools.stringDecompress(content))
				val basicContent = Processing.extractPlainText(page)

				// Identify page based on current model
				val contentArray = basicContent.split(" ")
				val results = categoriser.propertyClassifier.categorize(contentArray)
				val category = categoriser.propertyClassifier.getBestCategory(results)
				val confidence = categoriser.propertyClassifier.scoreMap(contentArray)

				Logger.trace(s"Page classification results for ${url.toString}: ${confidence}")

				if (category == "Property") {
					Logger.trace(s"Property page identified: ${url.toString}")
					rawDataPointProcessRouter ! ProcessBasicData(crawlMethod, urlChain, content, PropertyCategory())

					// Set scoring for page as property
					ScoringEngine.scorePages(urlChain, PropertyScore(), crawlMethod, visits)

					// Notify that the page is a property page to the cache
					cache.put(Tools.urlToIdentifier(url), "property", 1)
				}
				else {
					Logger.trace(s"Miscellaneous page identified: ${url.toString}")
				}

				if (trainingMode == "test") {
					val isProperty = propertyTrainingIdentifier.findFirstIn(url.toString).isDefined

					val dataToWrite = s"Categorised as ${category} - Is Property? ${isProperty} - Confidence ${confidence} - Url ${url}\n"
					Files.write(Paths.get(trainingOutput), dataToWrite.getBytes, StandardOpenOption.APPEND)
				}
			}
    	}

		case ClassifyPage(url, category) => {
			if (category == PropertyCategory()) {
				// Notify that the page is a property page to the cache
				cache.put(Tools.urlToIdentifier(url), "property", 1)
			}
			else {
				// Notify that the page is not a property page to the cache
				cache.put(Tools.urlToIdentifier(url), "property", 0)
			}
		}
  	}
}
