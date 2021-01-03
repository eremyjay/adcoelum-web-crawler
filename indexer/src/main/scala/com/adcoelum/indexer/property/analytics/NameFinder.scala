package com.adcoelum.indexer.property.analytics

import com.adcoelum.common.Configuration
import opennlp.tools.namefind.{NameFinderME, TokenNameFinderModel}

import scala.collection.mutable.ListBuffer

import scala.io.Source

/**
  * Created by jeremy on 13/02/2017.
  */
object NameFinder {
    private val nis = new java.io.FileInputStream(Configuration.nlpPath + (Configuration.config \ "nlp" \ "nameFinderModel").as[String])

    val nModel = new TokenNameFinderModel(nis)

    val nameListFile = {
        val path = (Configuration.config \ "indexer" \ "names" \ "nameList").as[String]
        if (!path.startsWith("/"))
            System.getProperty("user.dir") + "/" + path + "/"
        else if (path.startsWith("./"))
            System.getProperty("user.dir") + path.drop(1) + "/"
        else
            path
    }

    lazy val namesList = {
        val names = ListBuffer.empty[String]

        for (line <- Source.fromFile(nameListFile).getLines)
            names += line

        names.toList
    }

    val nameListMatcher = (Configuration.config \ "indexer" \ "names" \ "nameListMatcher").as[String]
}

class NameFinder {
    val nameFinder = new NameFinderME(NameFinder.nModel)
}