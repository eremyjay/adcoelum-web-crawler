package com.adcoelum.core

import com.adcoelum.common.{Configuration, Logger, Tools}

import scala.language.postfixOps


/**
  * The SearchBot class is an application class that acts as the command and control for a collection of crawlers
  * and indexers and any components that form part of a search architecture.  The SearchBot includes an interface
  * using Spark java servlet and encases the responder that handles instructions given to the command and control server
  */
object SearchBot extends App {
    var configFile = "./config.json"

    // Initialise all configuration items
    try {
        // Capture and handle arguments passed in to application
        args.length match {
            case 1 => {
                configFile = args(0).toString
            }
            case _ => {
                Logger.error("Not correct number of arguments for Search Bot instance - quitting", error = new Throwable)
                System.exit(1)
            }
        }

        Configuration.init
        Logger.info(s"Pre-loading SearchBot instance with process ID ${Configuration.processID}")

        // Determine whether process already exists - if so, abort - if not, create process lock file
        if (ProcessLock.anyExistingProcesses)
            throw new Exception("A command and control process is already running.")
        else
            ProcessLock.createProcessLock(Configuration.processID)

        // Ensure all necessary connectivity is available at start
        if (!Tools.ensureConnectivity)
            System.exit(1)

        // Start the processing engine to handle instructions give to the command and control
        val responder = new CommandControl
        responder.start
    }
    catch {
        case e: Exception => {
            Logger.error(s"Failed to start search bot due to error: ${e.getMessage}", error = e)
            System.exit(1)
        }
    }
}