package com.adcoelum.common

import java.net.URL

import com.adcoelum.data.DatabaseAccessor

abstract class ScoreType
case class PropertyScore(alreadyVisited: Boolean = false) extends ScoreType

/**
  * Created by jeremy on 2/12/2016.
  */
object ScoringEngine {
    val cache = DatabaseAccessor.cache

    val base = (Configuration.config \ "score" \ "scoreBasePower").as[Int]
    val propertyPageScore = (Configuration.config \ "score" \ "scoreValue").as[Int]

    val linkedToScore = 1

    def getScore(urlIdentifier: String): Int = {
        val score: Integer = cache.get[Int](urlIdentifier, "score")
        if (score == null) {
            cache.put(urlIdentifier, "score", 0)
            0
        }
        else
            score
    }

    def setScore(urlIdentifier: String, value: Int, crawlMethod: Int = 0, visits: Int = 0) = {
        if (performScoring(crawlMethod, visits))
            cache.put(urlIdentifier, "score", value)
    }

    def incrementScore(urlIdentifier: String, value: Int, crawlMethod: Int, visits: Int = 0): Int = {
        var score: Integer = cache.get[Int](urlIdentifier, "score")

        if (performScoring(crawlMethod, visits)) {
            if (score == null)
                score = 0

            score += value
            cache.put(urlIdentifier, "score", score)
        }

        score
    }

    def decrementScore(urlIdentifier: String, value: Int, crawlMethod: Int, visits: Int = 0): Int = {
        var score: Integer = cache.get[Int](urlIdentifier, "score")

        if (performScoring(crawlMethod, visits)) {
            if (score == null)
                score = 0

            score -= value
            cache.put(urlIdentifier, "score", score)
        }

        score
    }

    def resetScore(urlIdentifier: String, crawlMethod: Int, visits: Int = 0): Int = {
        val score = 0

        if (performScoring(crawlMethod, visits))
            cache.put(urlIdentifier, "score", score)

        score
    }

    def scorePages(urlChain: List[URL], scoreType: ScoreType, crawlMethod: Int, visits: Int = 0, overwrite: Boolean = false) = {
        if (performScoring(crawlMethod, visits)) {
            scoreType match {
                case PropertyScore(alreadyVisited) => {
                    // Handle whether to score the actual property page and also drop the root page of the site from scoring
                    val urlChainScoring = {
                        if (alreadyVisited)
                            urlChain.drop(1).dropRight(1)
                        else
                            urlChain.dropRight(1)
                    }

                    // Set scoring for page as property
                    var depth = {
                        if (alreadyVisited)
                            1
                        else
                            0
                    }

                    urlChainScoring foreach { url =>
                        val urlIdentifier = Tools.urlToIdentifier(url)
                        var score = (propertyPageScore / Math.pow(base, depth)).toInt

                        if (overwrite)
                            setScore(urlIdentifier, score, crawlMethod)
                        else
                            score = incrementScore(urlIdentifier, score, crawlMethod, visits)

                        if (alreadyVisited)
                            Logger.debug(s"Setting scoring without property page as ${score} for url - ${urlIdentifier}", Logger.VERBOSE_INTENSITY)
                        else
                            Logger.debug(s"Setting property page score as ${score} for url - ${urlIdentifier}", Logger.VERBOSE_INTENSITY)
                        depth += 1
                    }
                }
            }
        }
    }

    def performScoring(crawlMethod: Int, visits: Int) = {
        visits match {
            case 0 => true
            case _ => false
        }
    }
}
