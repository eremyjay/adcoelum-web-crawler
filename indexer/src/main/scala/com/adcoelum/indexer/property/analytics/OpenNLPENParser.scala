package com.adcoelum.indexer.property.analytics

import com.adcoelum.common.Configuration
import opennlp.tools.chunker.{ChunkerME, ChunkerModel}
import opennlp.tools.postag.{POSModel, POSTaggerME}
import opennlp.tools.tokenize.{TokenizerME, TokenizerModel}

/**
  * Created by jeremy on 13/02/2017.
  */

/**
  * Definition of the parser to be used for natural language processing
  * Processors are pulled from the appropriately defined path
  * The tokenizer, chunker and POS models are then established to allow processing
  *
  * Options for NLP processing
  * http://opennlp.sourceforge.net/models-1.5/
  * http://web.mit.edu/6.863/www/PennTreebankTags.html
  * http://www.clips.ua.ac.be/pages/mbsp-tags
  */
object OpenNLPSources {
    // Pull the bin files needed
    private val tis = new java.io.FileInputStream(Configuration.nlpPath + (Configuration.config \ "nlp" \ "tokenizerModel").as[String])
    private val cis = new java.io.FileInputStream(Configuration.nlpPath + (Configuration.config \ "nlp" \ "chunkerModel").as[String])
    private val pis = new java.io.FileInputStream(Configuration.nlpPath + (Configuration.config \ "nlp" \ "posModel").as[String])

    // Setup the models based on the bin files
    val tModel = new TokenizerModel(tis)
    val cModel = new ChunkerModel(cis)
    val pModel = new POSModel(pis)
}

class OpenNLPENParser {
    // Establish the tokenizer, chunkder and POS tagger
    val tokenzier = new TokenizerME(OpenNLPSources.tModel)
    val chunker = new ChunkerME(OpenNLPSources.cModel)
    val posTagger = new POSTaggerME(OpenNLPSources.pModel)
}

