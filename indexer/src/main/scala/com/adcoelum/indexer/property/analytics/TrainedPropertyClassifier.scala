package com.adcoelum.indexer.property.analytics

import java.io.{ByteArrayInputStream, FileInputStream}

import com.adcoelum.common.Configuration
import opennlp.tools.doccat.{DoccatModel, DocumentCategorizerME}

/**
  * Created by jeremy on 13/02/2017.
  */
object PropertyClassifier {
    private val pis = new FileInputStream(Configuration.nlpPath + (Configuration.config \ "nlp" \ "classifierModel").as[String])

    val pModel = new DoccatModel(pis)
}

class TrainedPropertyClassifier {
    val propertyClassifier = new DocumentCategorizerME(PropertyClassifier.pModel)
}