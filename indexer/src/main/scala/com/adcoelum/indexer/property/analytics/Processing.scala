package com.adcoelum.indexer.property.analytics

import com.adcoelum.common.{Configuration, Logger}
import opennlp.tools.util.Span

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.xml.{Elem, Node, NodeSeq, Text}

/**
  * Created by jeremy on 14/02/2017.
  */
object Processing {
    // Content replacers
    val replacer1 = "(?si)(<noscript([\\s]([^/][^>])*?)?>.*</noscript>)|(<script([\\s]([^/][^>])*?)?>.*?</script>)|(<code([\\s]([^/][^>])*?)?>.*?</code>)|(<style([\\s]([^/][^>])*?)?>.*?</style>)|(<a([\\s]([^/][^>])*?)?>.*?</a>)|(<!--.*?-->)"
    val replacer2 = "(?si)(<select([\\s]([^/][^>])*?)?>.*?</select>)|(<option([\\s]([^/][^>])*?)?>.*?</option>)|(<button([\\s]([^/][^>])*?)?>.*?</button>)|(<label([\\s]([^/][^>])*?)?>.*?</label>)|(<textarea([\\s]([^/][^>])*?)?>.*?</textarea>)|(<input([\\s]([^/][^>])*?)?>.*?</input>)"
    val replacer3 = "(?si)(</blockquote>)|(</caption>)|(</p>)|(</div>)|(</li>)|(</dt>)|(</h[0-9]>)|(</tr>)|(</td>)|(<br.*?>)"
    val replacer4 = "(?si)(<[^<>]+>)"
    val replacer5 = "(?si)(&[#a-z0-9]+;)|(http[s]?://[^ ]+?\\s)"
    val replacer6 = "(?si)[\\n\\r]+"
    val replacer7 = "(?si)@!!@"
    val replacer8 = "(?si)(_[ ]+)+"
    val replacer9 = "(?si)[\\s]+"

    val blankAttribute="=''"
    val space =  " "
    val sectionSeparator = "@!!@"
    val blank = ""
    val lineSeparator = " _ "
    val breakSeparator = "_ "
    val analysisText = "(?s)(?i)(suburb|market|area)[ ]+(profile|data|analysis).*(?-i)"
    val headText = "(?s)<head.*>.*?</head>"

    val metaTagName = "meta"
    val contentAttribute = "@content"
    val nameAttribute = "@name"
    val propertyAttribute = "@property"
    val httpEquivAttribute = "@http-equiv"
    val titleTagName = "title"


    /**
      * Extract all plain text from an xml document
      *
      * @param content
      * @return
      */
    def extractPlainText(content: Elem): String = {
        basicTextExtractor(content.toString)
    }

    /**
      * Pull all plain text excluding the head of the document
      *
      * @param content
      * @return
      */
    def extractPlainTextWithoutHead(content: Elem): String = {
        basicTextExtractor(removeHeadExtractor(content.toString()))
    }

    /**
      * Extract all text related directly to the property where possible
      *
      * @param content
      * @return
      */
    def extractPlainPropertyText(content: Elem): String = {
        removeAnalysisExtractor(basicTextExtractor(content.toString))
    }

    /**
      * Remove any analysis data (assume everything afterwards is purely analysis)
      *
      * @param rawContent
      * @return
      */
    def removeAnalysisExtractor(rawContent: String): String = {
        rawContent.replaceAll(analysisText, blank)
    }

    /**
      * Perform removal of any head tags and associated data within
      *
      * @param rawContent
      * @return
      */
    def removeHeadExtractor(rawContent: String): String = {
        rawContent.replaceAll(headText, space)
    }

    /**
      * Perform extraction of text and format it so that it is easily readable and processable
      *
      * @param rawContent
      * @return
      */
    def basicTextExtractor(rawContent: String): String = {
        var extractedContent = rawContent

        Logger.trace(s"Extracted content replaced 0:\n${extractedContent}", Logger.SUPER_VERBOSE_VERBOSE_INTENSITY)
        extractedContent = extractedContent
            .replaceAll(replacer1, space)
        Logger.trace(s"Extracted content replaced 1:\n${extractedContent}", Logger.SUPER_VERBOSE_VERBOSE_INTENSITY)
        extractedContent = extractedContent
            .replaceAll(replacer2, space)
        Logger.trace(s"Extracted content replaced 2:\n${extractedContent}", Logger.SUPER_VERBOSE_VERBOSE_INTENSITY)
        extractedContent = extractedContent
            .replaceAll(replacer3, sectionSeparator)
        Logger.trace(s"Extracted content replaced 3:\n${extractedContent}", Logger.SUPER_VERBOSE_VERBOSE_INTENSITY)
        extractedContent = extractedContent
            .replaceAll(replacer4, space)
        Logger.trace(s"Extracted content replaced 4:\n${extractedContent}", Logger.SUPER_VERBOSE_VERBOSE_INTENSITY)
        extractedContent = extractedContent
            .replaceAll(replacer5, blank)
        Logger.trace(s"Extracted content replaced 5:\n${extractedContent}", Logger.SUPER_VERBOSE_VERBOSE_INTENSITY)
        extractedContent = extractedContent
            .replaceAll(replacer6, blank)
        Logger.trace(s"Extracted content replaced 6:\n${extractedContent}", Logger.SUPER_VERBOSE_VERBOSE_INTENSITY)
        extractedContent = extractedContent
            .replaceAll(replacer7, lineSeparator)
        Logger.trace(s"Extracted content replaced 7:\n${extractedContent}", Logger.SUPER_VERBOSE_VERBOSE_INTENSITY)
        extractedContent = extractedContent
            .replaceAll(replacer8, breakSeparator)
        Logger.trace(s"Extracted content replaced 8:\n${extractedContent}", Logger.SUPER_VERBOSE_VERBOSE_INTENSITY)
        extractedContent = extractedContent
            .replaceAll(replacer9, space)
        Logger.trace(s"Extracted content replaced 9:\n${extractedContent}", Logger.SUPER_VERBOSE_VERBOSE_INTENSITY)

        extractedContent
    }

    /**
      * Extract a single data point from the first match within a source text string and extract the relevant
      * sub-grouping text within parentheses using a collection of regular expressions
      *
      * @param source
      * @param regexes
      * @param position
      * @return
      */
    def plainTextSingleDataPointFirstFinder(source: String, regexes: List[String], position: Integer = 0): Option[String] = {
        var outcome: Option[String] = None

        // Run through each regex
        regexes foreach { rgStr =>
            val regex = rgStr.r

            // Find first match
            outcome = regex findFirstMatchIn source match {
                // Obtain only the relevant subgroup requested
                case Some(result)    => Some(result.subgroups(position))
                case None            => None
            }

            // If something is found, stop and return
            if (outcome.isDefined)
                return outcome
        }

        return None
    }

    /**
      * Extract all single data points from all matches within a source text string and extract the relevant
      * sub-grouping text within parentheses using a collection of regular expressions
      *
      * @param source
      * @param regexes
      * @param position
      * @return
      */
    def plainTextSingleDataPointFirstGroupFinder(source: String, regexes: List[String], position: Integer = 0): List[String] = {
        var outcome: ListBuffer[String] = ListBuffer()

        // Run through each regex
        regexes foreach { rgStr =>
            val regex = rgStr.r

            // Find all matches and run through each
            val search = regex findAllMatchIn source
            search foreach { result =>
                // Obtain only the relevant subgroup requested
                outcome += result.subgroups(position)
            }

            // If something is found, stop and return
            if (outcome.nonEmpty)
                return outcome.toList
        }

        List()
    }

    /**
      * Extract from the first match within a source text string all the relevant
      * sub-grouping text within parentheses using a collection of regular expressions
      *
      * @param source
      * @param regexes
      * @return
      */
    def plainTextMultiDataPointFirstFinder(source: String, regexes: List[String]): Option[List[String]] = {
        var outcome: Option[List[String]] = None

        // Run through each regex
        regexes foreach { rgStr =>
            val regex = rgStr.r

            // Find first match
            regex findFirstMatchIn source match {
                // Obtain only the relevant subgroup requested
                case Some(result)    => outcome = Some(result.subgroups)
                case None            => None
            }

            // If something is found, stop and return
            if (outcome.isDefined)
                return outcome
        }

        return None
    }

    /**
      * Extract from all matches within a source text string all the relevant
      * sub-grouping text within parentheses using a collection of regular expressions
      *
      * @param source
      * @param regexes
      * @return
      */
    def plainTextMultiDataPointAllFinder(source: String, regexes: List[String]): List[List[String]] = {
        var outcome: ListBuffer[List[String]] = ListBuffer()

        // Run through each regex
        regexes foreach { rgStr =>
            val regex = rgStr.r

            // Find all matches and run through each
            regex findAllMatchIn source foreach { result =>
                // Obtain all subgroups
                outcome += result.subgroups
            }
        }

        // Return the result
        outcome.toList
    }


    /**
      * Extract from all matches within a source text string all the relevant
      * sub-grouping text within parentheses using a collection of regular expressions
      * include at the beginning in each result the finder used by number
      * Note: subgroups in this method start at 1, due to selector being included at position 0
      *
      * @param source
      * @param regexes
      * @return
      */
    def plainTextMultiDataPointAllFinderWithSelector(source: String, regexes: List[String]): List[List[String]] = {
        var outcome: ListBuffer[List[String]] = ListBuffer()

        var counter = 1

        // Run through each regex
        regexes foreach { rgStr =>
            val regex = rgStr.r

            // Find all matches and run through each
            regex findAllMatchIn source foreach { result =>
                // Obtain all subgroups
                outcome += (counter.toString :: result.subgroups)
            }

            counter += 1
        }

        // Return the result
        outcome.toList
    }

    /**
      * Extract from all matches within a source text string the first match using a collection of regular expressions
      *
      * @param source
      * @param regexes
      * @return
      */
    def plainTextFullDataFirstFinder(source: String, regexes: List[String]): Option[String] = {
        var outcome: Option[String] = None

        // Run through each regex
        regexes foreach { rgStr =>
            val regex = rgStr.r

            // Find the first match
            outcome = regex findFirstIn source match {
                case Some(result)    => Some(result)
                case None            => None
            }

            // If something is found, stop and return
            if (outcome.isDefined)
                return outcome
        }

        return None
    }

    /**
      * Extract from all matches within a source text string all the matches using a collection of regular expressions
      *
      * @param source
      * @param regexes
      * @return
      */
    def plainTextFullDataAllFinder(source: String, regexes: List[String]): Option[List[String]] = {
        var outcome: ListBuffer[String] = ListBuffer.empty

        // Run through each regex
        regexes foreach { rgStr =>
            val regex = rgStr.r

            // Find all matches and run through each
            regex findAllIn source foreach { result =>
                outcome += result
            }

            // If something is found, stop and return
            if (outcome.nonEmpty)
                return Some(outcome.toList)

        }

        None
    }


    def attributeEquals(name: String, value: String)(node: Node) = {
        node.attribute(name).filter(_.text == value).isDefined
    }

    def attributeContains(name: String, value: String)(node: Node) = {
        node.attribute(name).filter(_.text.contains(value)).isDefined
    }

    def attributeValueEquals(value: String)(node: Node): Boolean = {
        node.attributes.exists(_.value.text == value)
    }

    def attributeValueContains(value: String)(node: Node): Boolean = {
        node.attributes.exists(_.value.text.contains(value))
    }

    def textEquals(value: String)(node: Node): Boolean = {
        node.child.collect {case Text(t) => t}.map(_.trim).mkString(" ").trim.toLowerCase == value.toLowerCase
    }

    def textContains(value: String)(node: Node): Boolean = {
        node.child.collect {case Text(t) => t}.map(_.trim).mkString(" ").trim.toLowerCase.contains(value.toLowerCase)
    }

    def childAttributeValueEquals(value: String)(node: Node): Boolean = {
        node.child foreach { child =>
            if (child.attributes.exists(_.value.text == value))
                return true
        }

        false
    }

    def childAttributeValueContains(value: String)(node: Node): Boolean = {
        node.child foreach { child =>
            if (child.attributes.exists(_.value.text.contains(value)))
                return true
        }

        false
    }


    /**
      * Search for a match in an element tree to determine whether they contain any part of the value in the text and
      * return the first matching element
      *
      * @param content
      * @param value
      * @return
      */
    def elementTextContainsDataPointFirstFinderAsElement(content: Elem, value: String): Option[Elem] = {
        // Search the element for the attribute and if it has a value that contains the search criteria
        val data: NodeSeq = content \\ "_" filter textContains(value)

        // If a result is found, stop and return the element text
        if (data.length > 0 && data.head.isInstanceOf[Elem])
            return Some(data.head.asInstanceOf[Elem])

        None
    }

    /**
      * Search for a match in an element tree to determine whether they contain any part of the value in the text and
      * return all matching elements
      *
      * @param content
      * @param value
      * @return
      */
    def elementTextContainsDataPointAllFinderAsElement(content: Elem, value: String): List[Elem] = {
        var result: ListBuffer[Elem] = ListBuffer()

        // Search the element for the attribute and if it has a value that contains the search criteria
        val list: NodeSeq = content \\ "_" filter textContains(value)

        list foreach { element =>
            if (element.isInstanceOf[Elem])
                result += element.asInstanceOf[Elem]
        }

        result.toList
    }


    /**
      * Search for a match in an element tree to determine whether they contain any part of the value in any of the attributes and
      * return the first matching element
      *
      * @param content
      * @param value
      * @return
      */
    def attributesContainsDataPointFirstFinderAsElement(content: Elem, value: String): Option[Elem] = {
        // Search the element for the attribute and if it has a value that contains the search criteria
        val data: NodeSeq = content \\ "_" filter attributeValueContains(value)

        // If a result is found, stop and return the element text
        if (data.length > 0 && data.head.isInstanceOf[Elem])
            return Some(data.head.asInstanceOf[Elem])

        None
    }

    /**
      * Search for a match in an element tree to determine whether they contain any part of the value in any of the attributes and
      * return all matching elements
      *
      * @param content
      * @param value
      * @return
      */
    def attributesContainsDataPointAllFinderAsElement(content: Elem, value: String): List[Elem] = {
        var result: ListBuffer[Elem] = ListBuffer()

        // Search the element for the attribute and if it has a value that contains the search criteria
        val list: NodeSeq = content \\ "_" filter attributeValueContains(value)

        list foreach { element =>
            if (element.isInstanceOf[Elem])
                result += element.asInstanceOf[Elem]
        }

        result.toList
    }


    /**
      * Search for a match in the child elements to determine whether they contain any part of the value in the text and
      * return the first matching parent element
      *
      * @param content
      * @param value
      * @return
      */
    def childAttributesContainsDataPointFirstFinderAsElement(content: Elem, value: String): Option[Elem] = {
        // Search the child elements for the attribute and if it has a value that contains the search criteria
        val data: NodeSeq = content \\ "_" filter childAttributeValueContains(value)

        // If a result is found, stop and return the element text
        if (data.length > 0 && data.head.isInstanceOf[Elem])
            return Some(data.head.asInstanceOf[Elem])

        None
    }


    /**
      * Search for a match in the child elements to determine whether they contain any part of the value in the text and
      * return all matching parent elements
      *
      * @param content
      * @param value
      * @return
      */
    def childAttributesContainsDataPointAllFinderAsElement(content: Elem, value: String): List[Elem] = {
        var result: ListBuffer[Elem] = ListBuffer()

        // Search the element for the attribute and if it has a value that contains the search criteria
        val list: NodeSeq = content \\ "_" filter childAttributeValueContains(value)

        list foreach { element =>
            if (element.isInstanceOf[Elem])
                result += element.asInstanceOf[Elem]
        }

        result.toList
    }


    /**
      * Search for a match in an element tree for a list of attributes to determine whether they contain any part of the value and
      * return the text within the element
      *
      * @param content
      * @param attributes
      * @param value
      * @return
      */
    def elementAttributeContainsDataPointFirstFinder(content: Elem, attributes: List[String], value: String): Option[String] = {
        // Loop through each attribute
        attributes foreach { attribute =>
            // Search the element for the attribute and if it has a value that contains the search criteria
            val data: NodeSeq = content \\ "_" filter attributeContains(attribute, value)

            // If a result is found, stop and return the element text
            if (data.length > 0)
                return Some(data.head.text)
        }

        None
    }

    /**
      * Search for a match in an element tree for a list of attributes to determine whether they contain any part of the value and
      * return the text for each matching element
      *
      * @param content
      * @param attributes
      * @param value
      * @return
      */
    def elementAttributeContainsDataPointAllFinder(content: Elem, attributes: List[String], value: String): List[String] = {
        var result: ListBuffer[String] = ListBuffer()

        // Loop through each attribute
        attributes foreach { attribute =>
            // Search the element for the attribute and if it has a value that contains the search criteria
            val list: NodeSeq = content \\ "_" filter attributeContains(attribute, value)
            list foreach { element =>
                val text = element.text

                // If a result is found, add the element text to the list
                if (text != "")
                    result += text
            }
        }

        result.toList
    }

    /**
      * Search for a match in an element tree for a list of attributes to determine whether they contain any part of the value and
      * return the first matching attribute value
      *
      * @param content
      * @param attributes
      * @param value
      * @return
      */
    def attributeAttributeContainsDataPointFirstFinder(content: Elem, attributes: List[String], value: String): Option[String] = {
        // Loop through each attribute
        attributes foreach { attribute =>
            // Search the element for the attribute and if it has a value that contains the search criteria
            val data: NodeSeq = content \\ "_" filter attributeContains(attribute, value)

            // If a match is found, pull the attribute
            if (data.length > 0) {
                val value = data \ s"@${attribute}"

                // Ensure the attribute match is true
                if (value.length > 0) {
                    val text = value.text

                    // Pull the value of the atttribute, ensuring it is non-empty and return
                    if (text != "")
                        return Some(data.text)
                }
            }
        }

        None
    }

    /**
      * Search for a match in an element tree and find the first match and extract the relevant sub-grouping text
      * within parentheses using a collection of regular expressions
      *
      * @param content
      * @param regexes
      * @param position
      * @return
      */
    def rawSourceContainsDataPointFirstFinder(content: Elem, regexes: List[String], position: Integer = 0): Option[String] = {
        var outcome: Option[String] = None

        // Run through each regex
        regexes foreach { rgStr =>
            val regex = rgStr.r

            // Find first match
            outcome = regex findFirstMatchIn content.toString match {
                // Obtain only the relevant subgroup requested
                case Some(result)    => Some(result.subgroups(position))
                case None            => None
            }

            // If something is found, stop and return
            if (outcome.isDefined)
                return outcome
        }

        return None
    }

    /**
      * Search for a match in an element tree and find the first match and extract all the sub-grouping text
      * within parentheses using a collection of regular expressions
      *
      * @param content
      * @param regexes
      * @return
      */
    def rawSourceContainsMultiDataPointFirstFinder(content: Elem, regexes: List[String]): Option[List[String]] = {
        var outcome: Option[List[String]] = None

        // Run through each regex
        regexes foreach { rgStr =>
            val regex = rgStr.r

            // Find all matches and run through each
            outcome = regex findFirstMatchIn content.toString match {
                // Obtain all the subgroups
                case Some(result)    => Some(result.subgroups)
                case None            => None
            }

            // If something is found, stop and return
            if (outcome.isDefined)
                return outcome
        }

        return None
    }

    /**
      * Locate the title tag in an xml element tree
      *
      * @param content
      * @return
      */
    def titleTag(content: Elem): String = {
        val title: NodeSeq = content \\ "title"

        if (title.length > 0)
            title.head.text
        else
            ""
    }

    /**
      * Locate the meta tags in an xml element tree and pull the results a hashmap
      *
      * @param content
      * @return
      */
    def metaTags(content: Elem): Map[String, String] = {
        val result: mutable.HashMap[String, String] = mutable.HashMap[String, String]()

        // Find all the meta tags
        val list: NodeSeq = content \\ metaTagName

        // Review each meta tag and pull the content
        list foreach { element =>
            val data = element \ contentAttribute

            if (content.length > 0) {
                if ((element \ nameAttribute).length > 0)
                    result += ((element \ nameAttribute).head.text.replace(".", "_") -> data.text)
                else if ((element \ propertyAttribute).length > 0)
                    result += ((element \ propertyAttribute).head.text.replace(".", "_")  -> data.text)
                else if ((element \ httpEquivAttribute).length > 0)
                    result += ((element \ httpEquivAttribute).head.text.replace(".", "_")  -> data.text)
            }
        }

        val titles: NodeSeq = content \\ titleTagName

        titles foreach { title =>
            if (title.text.length > 0)
                result += (titleTagName -> title.text)
        }

        result.toMap
    }

    /**
      * Extract the meta tag results from metaTags method and convert them to a continuous string
      *
      * @param metaTags
      * @return
      */
    def metaTagsAsText(metaTags: Map[String, String]): String = {
        var meta = " _ "

        metaTags foreach { tag =>
            meta += tag._1 + " : " + tag._2 + " _ "
        }

        meta
    }

    /**
      *
      * @param selector
      * @param components
      * @return
      */
    def fullAddressConstructor(selector: Int, components: List[String]): String = {
        selector match {
            case 1 => {
                    components(0) + " " + components(1) + " " + components(2) +  " " + components(3) +  " " + // Number
                        components(4) + " " + components(6) + " " + // Street
                        components(7) +  " " + // Suburb
                        components(9) +  " " + components(10) + " " + components(11) // State and Postcode
            }
            case 2 => {
                    components(5) + " " + components(6) + " " + components(7) +  " " + components(8) +  " " + // Number
                        components(9) + " " + components(11)  +  " " + // Street
                        components(0) + " " + // Suburb
                        components(2) + " " + components(3) +  " " + components(4) // State and Postcode
            }
            case 3 => {
                    components(0) + " " + components(2) + " " + // Street
                        components(3) +  " " + // Suburb
                        components(5) +  " " + components(6) + " " + components(7) // State and Postcode
            }
            case 4 => {
                    components(5) + " " + components(7) +  " " +  // Street
                        components(0) + " " + // Suburb
                        components(2) + " " + components(3) +  " " + components(4) // State and Postcode
            }
            case 5 => {
                    components(0) + " " + // Suburb
                        components(2) + " " + components(3) +  " " + components(4) // State and Postcode
            }
            case _ => {
                ""
            }
        }

    }

    /**
      *
      * @param selector
      * @param components
      * @return
      */
    def queryAddressConstructor(selector: Int, components: List[String]): String = {
        selector match {
            case 1 => {
                components(4) + " " + components(6) + " " + // Street
                    components(7) +  " " + // Suburb
                    components(9) +  " " + components(10) + " " + components(11) // State and Postcode
            }
            case 2 => {
                components(9) + " " + components(11)  +  " " + // Street
                    components(0) + " " + // Suburb
                    components(2) + " " + components(3) +  " " + components(4) // State and Postcode
            }
            case 3 => {
                components(0) + " " + components(2) + " " + // Street
                    components(3) +  " " + // Suburb
                    components(5) +  " " + components(6) + " " + components(7) // State and Postcode
            }
            case 4 => {
                components(5) + " " + components(7) +  " " +  // Street
                    components(0) + " " + // Suburb
                    components(2) + " " + components(3) +  " " + components(4) // State and Postcode
            }
            case 5 => {
                components(0) + " " + // Suburb
                    components(2) + " " + components(3) +  " " + components(4) // State and Postcode
            }
            case _ => {
                ""
            }
        }
    }
}
