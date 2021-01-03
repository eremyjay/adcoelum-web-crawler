package com.adcoelum.indexer.property.workers

import java.nio.file.{Files, Paths, StandardOpenOption}

import akka.actor.Actor
import com.adcoelum.actors.{ProcessAdvancedDataPoints, PropertyCategory, UpdateProperty}
import com.adcoelum.common.{Configuration, Logger, Tools}
import com.adcoelum.indexer.property.analytics.{NameFinder, OpenNLPENParser}
import opennlp.tools.util.Span

import scala.collection.mutable.{HashMap, ListBuffer}
import scala.language.postfixOps
import scala.util.matching.Regex


/**
  * The advanced data point processor class currently handles advanced data processing involving natural language processing
  * The focus of this class is currently on the capture of nouns for future tagging usage
  * However, future versions of the class are expected to provide other forms of advanced data processing
  */
class AdvancedDataPointProcessor extends Actor {
    // Establish connections to local actors
    val propertyFactoryRouter = context.actorSelection("/user/property_factory_router")

    val nameFinderMode = (Configuration.config \ "indexer" \ "names" \ "mode").as[String]

    // Define instance of parser
    val parser = new OpenNLPENParser

    // Define identifiers, filters and search items
    val dataSourcesIdentifier = "sources"
    val nounsIdentifier = "nouns"
    val agentsIdentifier = "agents"
    val tagSearch = List("NP")
    val tagFilter = "DT|PRP|CC|CD".r
    val space = " "
    val symbolFilter = "[!@#$%^&*()~`_+=|\\\\:;<>,.?/\\[\\]-]".r
    val apostrophe = '\''
    val prefApostrophe = '’'

    val nameFinder = new NameFinder

    val trainingOutput = {
        val path = (Configuration.config \ "indexer" \ "names" \ "trainingOutput").as[String]
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
        // Handle processing of advanced data points
        case ProcessAdvancedDataPoints(crawlMethod, address, rawContent, content, category, dataIdentifier) => {
            try {
                Logger.trace(s"Processing advanced data points: ${address}")

                // Set up data points and source data for processing
                val dataPoints: HashMap[String, Any] = new HashMap[String, Any]()
                val decompressedRawContent = Tools.stringDecompress(rawContent)

                // Establish data attributes for specific data set
                val attributeDataPoints = s"$dataSourcesIdentifier.$dataIdentifier."

                // Find all nouns in the raw content
                val nouns: List[String] = plainTextOpenNLPChunker(decompressedRawContent, tagSearch, tagFilter, false, symbolFilter, 4)
                val storableNouns: ListBuffer[String] = new ListBuffer[String]()

                // For each noun found, place it in the nouns store
                nouns foreach { noun =>
                    storableNouns += noun
                }

                dataPoints += (attributeDataPoints+nounsIdentifier -> storableNouns)
                dataPoints += (attributeDataPoints+agentsIdentifier -> findAgents(decompressedRawContent))

                // Pass the data on for storage
                if (category == PropertyCategory())
                    propertyFactoryRouter ! UpdateProperty(crawlMethod, address, dataPoints, dataIdentifier)
            }
            catch {
                case e: Throwable => {
                    Logger.error(s"""Error processing advanced data points for ${address}: ${e.getMessage}""", error = e)
                }
            }
        }
    }

    /**
      * A critical method for advanced data processing that pulls apart text to find and match various forms
      * of grammar based on natural language processing techniques
      *
      * @param source
      * @param nlpOptions
      * @param maxWords
      * @param noPunctuation
      * @return
      */
    def plainTextOpenNLPChunker(source: String, nlpOptions: List[String], tagFiltering: Regex, tagFilteringInclusive: Boolean,
                                symbolFiltering: Regex, maxWords: Int = 1, minWords: Int = 1,
                                noPunctuation: Boolean = true): List[String] = {
        try {
            var phrases = collection.mutable.Set[String]()

            // Clean up the text to remove bad data
            val cleanSource = source.replace(apostrophe, prefApostrophe)

            // Pull all the words, perform tagging and chunk the data
            val words = parser.tokenzier.tokenize(cleanSource)
            val pos = parser.posTagger.tag(words)
            val tags = parser.chunker.chunk(words, pos)

            var chunk = ListBuffer.empty[String]
            var counter = 0

            // Run through each tagged chunk, matching on the noun phrases
            tags foreach { tag =>
                tag match {
                    // Match on the beginning of a noun phrase
                    case "B-NP" => {
                        // Push the old matching chunk into phrases
                        if (chunk.nonEmpty && chunk.length >= minWords && chunk.length <= maxWords)
                            phrases += chunk.mkString(space)

                        // Handle creation of the new chunk
                        if (((!tagFilteringInclusive && (tagFiltering findFirstIn pos(counter)).isEmpty) ||
                              tagFilteringInclusive && (tagFiltering findFirstIn pos(counter)).nonEmpty)
                            && (symbolFiltering findFirstIn words(counter)).isEmpty)
                            chunk += words(counter)
                        else
                            chunk = ListBuffer.empty[String]
                    }
                    // Match on within a noun phrase
                    case "I-NP" => {
                        // Add a new noun to the existing chunk
                        if (((!tagFilteringInclusive && (tagFiltering findFirstIn pos(counter)).isEmpty) ||
                            tagFilteringInclusive && (tagFiltering findFirstIn pos(counter)).nonEmpty)
                            && (symbolFiltering findFirstIn words(counter)).isEmpty)
                            chunk += words(counter)
                        else {
                            // Push the old matching chunk into phrases
                            if (chunk.nonEmpty && chunk.length >= minWords && chunk.length <= maxWords)
                                phrases += chunk.mkString(space)

                            // Create a new chunk because there are no more nouns left
                            chunk = ListBuffer.empty[String]
                        }
                    }
                    // Handle non-match case, ensuring any old chunk is pushed into phrases
                    case _ => {
                        if (chunk.nonEmpty && chunk.length >= minWords && chunk.length <= maxWords)
                            phrases += chunk.mkString(space)

                        chunk = ListBuffer.empty[String]
                    }
                }

                counter += 1
            }

            phrases.toList
        }
        catch {
            case e: Throwable => Logger.warn("Failed to parse nouns - skipping", error = e)

            List.empty[String]
        }
    }


    def findAgents(plainText: String): List[String] = {
        if (nameFinderMode == "ner") {
            findNamesInText(plainText, "agent")._1
        }
        else if (nameFinderMode == "chunker") {
            val potentialNames = plainTextOpenNLPChunker(plainText, tagSearch, "NNP".r, true, symbolFilter, 4, 2)

            val names = ListBuffer.empty[String]

            potentialNames foreach { potentialName =>
                if (NameFinder.namesList exists { name => (NameFinder.nameListMatcher.format(name.toLowerCase).r findFirstIn potentialName.toLowerCase).isDefined })
                    names += potentialName
            }

            names.toList
        }
        else if (nameFinderMode == "train") {
            val potentialNames = plainTextOpenNLPChunker(plainText, tagSearch, "NNP".r, true, symbolFilter, 4, 2)

            val names = ListBuffer.empty[String]

            potentialNames foreach { potentialName =>
                if (NameFinder.namesList exists { name => (NameFinder.nameListMatcher.format(name.toLowerCase).r findFirstIn potentialName.toLowerCase).isDefined })
                    names += potentialName
            }

            var outputText = plainText

            names foreach { name =>
                outputText = outputText.replace(name, s" <START:agent> $name <END> ")
            }

            val dataToWrite = s"$outputText\n"
            Files.write(Paths.get(trainingOutput), dataToWrite.getBytes, StandardOpenOption.APPEND)

            names.toList
        }
        else {
            List.empty[String]
        }
    }

    def findNamesInText(source: String, nameTypeMatcher: String, probabilityThreshold: Double = 0.0): Tuple2[List[String], List[Span]] = {
        val sourceAsArray = source.split(Array(' ', '_'))

        val nameTokens = nameFinder.nameFinder.find(sourceAsArray)
        val nameString = Span.spansToStrings(nameTokens, sourceAsArray)
        val names: ListBuffer[String] = ListBuffer.empty[String]

        var counter = 0
        nameTokens foreach { token =>
            val probability = token.getProb
            val nameType = token.getType
            //Logger.info(s"Identified Name: ${token.toString} - probability ${token.getProb} - type ${nameType}")

            if (nameType == nameTypeMatcher && probability > probabilityThreshold)
                names += nameString(counter)

            counter += 1
        }

        nameFinder.nameFinder.clearAdaptiveData

        new Tuple2(names.toList, nameTokens.toList)
    }

    // http://www.outpost9.com/files/WordLists.html
    // http://www.clips.ua.ac.be/pages/mbsp-tags
}