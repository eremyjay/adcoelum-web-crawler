package com.adcoelum.client

import com.adcoelum.actors.Statistic
import com.adcoelum.common.{Logger, Tools}
import com.adcoelum.downloader.Downloader

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.FiniteDuration

/**
  * Define the Client Registry object
  * The purpose of this object is to hold a register of all active browser clients for the Crawler instance
  * This helps with managing effective closure of the clients and for future handling of dead clients to respawn: TODO
  */
object ClientRegistry {
    // Establish clients list
    var clients: ListBuffer[HttpClient] = new ListBuffer[HttpClient]

    /**
      * Add a client to the register
      *
      * @param client
      * @return
      */
    def add(client: HttpClient) = {
        Logger.debug(s"Adding browser client to the registry: ${client.id}", Logger.VERBOSE_INTENSITY)
        clients += client
        Downloader.activityMonitor ! Statistic("browser", Map("browser_increment" -> 1))
    }

    def remove(client: HttpClient) = {
        Logger.debug(s"Removing browser client to the registry: ${client.id}", Logger.VERBOSE_INTENSITY)

        var counter = 0
        clients foreach { registeredClient =>
            if (client.id == registeredClient.id)
                clients.remove(counter)
            else
                counter += 1
        }
    }

    /**
      * Close and remove all clients
      */
    def closeAll = {
        Logger.debug("Shutting down all browser clients", Logger.VERBOSE_INTENSITY)
        clients foreach { client =>
            client.shutdown
            Downloader.activityMonitor ! Statistic("browser", Map("browser_decrement" -> 1))
        }

        clients.clear
    }

    /**
      * Close and remove all clients with a predefined delay
      *
      * @param delay
      */
    def closeAllWithDelay(delay: FiniteDuration) = {
        Tools.sleep(delay)
        closeAll
    }
}
