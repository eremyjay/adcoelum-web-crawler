package com.adcoelum.core

import java.io.File
import java.nio.file.{Files, Paths}
import java.text.{NumberFormat, SimpleDateFormat}

import com.adcoelum.common.{Configuration, Logger, HttpServer, Tools}
import com.adcoelum.data.DatabaseAccessor

import scala.collection.immutable.ListMap
import scala.collection.mutable.ListBuffer


/**
  * Created by jeremy on 1/01/2017.
  */
class RestfulApi extends HttpServer(Configuration.host, (Configuration.config \ "restapi" \ "port").as[Int]) {
    // Establish the spark server
    val port = (Configuration.config \ "restapi" \ "port").as[Int]
    Logger.info(s"Loading REST API interface for command and control on host ${Configuration.host}:${port}")

    // Define api commands
    get("/") {
        (req, res) => {
            ""
        }
    }

    post("/") {
        (req, res) => {
            ""
        }
    }
}
