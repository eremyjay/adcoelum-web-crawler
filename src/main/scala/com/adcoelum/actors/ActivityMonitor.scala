package com.adcoelum.actors

import java.lang.management.ManagementFactory

import akka.actor.Actor
import akka.util.Timeout
import com.adcoelum.common.{Configuration, Logger}
import com.adcoelum.data.DatabaseAccessor

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.HashMap
import scala.concurrent.duration._
import scala.language.postfixOps



/**
  * The Activity Monitor is responsible for providing statistical information about the behaviour of crawlers, indexers
  * and their actor systems as well as the ability to work out when/if an actor system has finished what it needs to do
  * based on activity, using the terminator parameter to decide whether anything needs to be done.
  *
  * @param commander
  * @param appName
  * @param terminator
  */
class ActivityMonitor(commander: String, appName: String, terminator: Boolean, basicStats: Boolean, tickDestination: String) extends Actor {
    implicit val timeout = Timeout(5 seconds)

    // Threshold count to determine when sufficient convinced that an actor system has no more activity
    val terminateThreshold = (Configuration.config \ "activity" \ "terminateThreshold").as[Int]

    // Connect to the data collection
    val monitorData = DatabaseAccessor.collection("activity")

    // Set tracking values
    var startTime = System.currentTimeMillis
    var registeredRouters = 0
    var routersCount = mutable.Map.empty[String, Int]
    var activeCheckCount = 0

    // Statistical Data
    val statistics: TrieMap[String, Any] = TrieMap.empty[String, Any]

    /**
      * Establish the types of messages that will be handled by the actor
      *
      * @return
      */
    def receive = {
        // Perform a tick and check activity on all actors
        case Tick => {
            context.actorSelection(tickDestination) ! TriggerActivity()

            // Set activity information to update
            val values: HashMap[String, Any] = HashMap.empty

            if (basicStats) {
                memoryStatistics

                routersCount foreach { nameCount =>
                    values += ("routers." + nameCount._1 -> nameCount._2)
                }
            }

            if (registeredRouters != 0)
                values += ("active_check_count" -> Math.floor(activeCheckCount / registeredRouters))

            statistics("elapsed_time") = System.currentTimeMillis - startTime
            values += ("elapsed_time" -> statistics("elapsed_time").asInstanceOf[Long])

            statistics foreach { statistic =>
                values += statistic
            }

            updateData(values)

            // Handle termination case
            if (terminator && registeredRouters != 0 && Math.floor(activeCheckCount / registeredRouters) > terminateThreshold)
                terminate
        }

        // Handle an activity check with message count
        case Activity(name, prevCount, currentCount) => {
            // Determine whether to increment the count or reset
            if (prevCount == currentCount)
                activeCheckCount += 1
            else
                activeCheckCount = 0

            routersCount(name) = currentCount
        }

        // Register a router
        case RegisterRouter(name) => {
            val values: HashMap[String, Any] = HashMap.empty

            // Initialise the tracking for the actor system if not already performed
            if (registeredRouters == 0)
                initialize

            // Initialise the value for the router
            values += ("routers."+name -> 0)
            updateData(values)

            // Increment the registered routers count
            registeredRouters += 1

            Logger.trace(s"Registering router for $appName: $name with router count $registeredRouters")
        }

        case TerminateActivity() => {
            terminate
        }

        case Statistic(stat, data) => {
            handleStatistic(stat, data)
        }
    }

    /**
      * Initialise the data for an actor system
      *
      * @return position in monitors array
      */
    def initialize = {
        Logger.trace(s"Initializing Monitor Data for $appName")
        val filter = Map("commander" -> commander)

        // Set initial values
        startTime = System.currentTimeMillis
        val data = Map(
            s"monitors.${appName}" -> Map("time_start" -> startTime, "time_end" -> 0)
        )

        monitorData.update(data, filter)
    }


    /**
      * Update the data for tracking a particular actor system
      *
      * @param values
      * @return
      */
    def updateData(values: HashMap[String, Any]) = {
        try {
            val monitorValues: HashMap[String, Any] = HashMap.empty

            values foreach { value =>
                monitorValues += (s"monitors.${appName}.${value._1}" -> value._2)
            }

            // Setup data for writing
            val data = monitorValues.toMap

            // Set query information against which to set data
            val filter: Map[String, Any] = Map("commander" -> commander)

            monitorData.update(data, filter)
        }
        catch {
            case e: Throwable => Logger.warn(s"Write to Monitor Data failed: ${e.getMessage}", error = e)
        }
    }

    def terminate() = {
        val now = System.currentTimeMillis
        Logger.info(s"Preparing for termination of app Actor System: ${appName} at ${now}")
        val values: HashMap[String, Any] = HashMap.empty

        values += ("time_end" -> now)
        statistics foreach { statistic =>
            values += statistic
        }

        updateData(values)

        // Shut down the actor system
        context.system.shutdown
    }

    def handleStatistic(stat: String, data: Map[String, Any]) = {
        stat match {
            case "page" => {
                statistics("page_total_download_elapsed_time") = statistics.getOrElseUpdate("page_total_download_elapsed_time", 0L).asInstanceOf[Long] + data.getOrElse("download_time", 0L).asInstanceOf[Long]
                statistics("page_total_elapsed_time") = statistics.getOrElseUpdate("page_total_elapsed_time", 0L).asInstanceOf[Long] + data.getOrElse("total_page_time", 0L).asInstanceOf[Long]
                statistics("page_total_download_size") = statistics.getOrElseUpdate("page_total_download_size", 0).asInstanceOf[Int] + data.getOrElse("download_size", 0).asInstanceOf[Int]
                if (statistics.getOrElse("page_download_count", 1).asInstanceOf[Int] != 0)
                    statistics("page_download_time_rate") = statistics.getOrElse("page_total_download_elapsed_time", 0L).asInstanceOf[Long] / statistics.getOrElse("page_download_count", 1).asInstanceOf[Int]
                if (statistics.getOrElse("page_download_count", 1).asInstanceOf[Int] != 0)
                    statistics("page_total_time_rate") = statistics.getOrElse("page_total_elapsed_time", 0L).asInstanceOf[Long] / statistics.getOrElse("page_download_count", 1).asInstanceOf[Int]

                data foreach { item =>
                    if (item._1.startsWith("browsers")) {
                        if (item._1.contains("last_page"))
                            statistics(item._1) = item._2.asInstanceOf[String]
                        else if (item._1.contains("last_download_time"))
                            statistics(item._1) = item._2.asInstanceOf[Long]
                        else if (item._1.contains("state"))
                            statistics(item._1) = item._2.asInstanceOf[String]
                        else if (item._1.contains("action")) {
                            statistics(item._1) = item._2.asInstanceOf[String]

                            statistics(item._1) match {
                                case "downloading" =>
                                    statistics("page_download_attempt_count") = statistics.getOrElseUpdate("page_download_attempt_count", 0).asInstanceOf[Int] + 1
                                case "downloaded" =>
                                    statistics("page_download_count") = statistics.getOrElseUpdate("page_download_count", 0).asInstanceOf[Int] + 1
                                case _ =>
                            }
                        }
                    }
                }
            }
            case "links" => {
                statistics("frontier_links_processed") = statistics.getOrElseUpdate("frontier_links_processed", 0).asInstanceOf[Int] + data.getOrElse("links_processed", 0).asInstanceOf[Int]
                // statistics("cumulative_links_depth") = statistics.getOrElseUpdate("cumulative_links_depth", 0).asInstanceOf[Int] + data.getOrElse("links_depth", 0).asInstanceOf[Int]
            }
            case "frontier" => {
                statistics("frontier_pages") = statistics.getOrElseUpdate("frontier_pages", 0).asInstanceOf[Int] + data.getOrElse("frontier_increment", 0).asInstanceOf[Int] - data.getOrElse("frontier_decrement", 0).asInstanceOf[Int]
                statistics("frontier_pages_completed") = statistics.getOrElseUpdate("frontier_pages_completed", 0).asInstanceOf[Int] + data.getOrElse("page_downloaded", 0).asInstanceOf[Int]
                statistics("frontier_pages_elapsed_time_remaining") = statistics.getOrElse("frontier_pages_completed_time_rate", 0L).asInstanceOf[Long] * statistics.getOrElseUpdate("frontier_pages", 0).asInstanceOf[Int]
                if (statistics.getOrElseUpdate("frontier_pages_completed", 1).asInstanceOf[Int] != 0)
                    statistics("frontier_pages_completed_time_rate") = statistics.getOrElse("elapsed_time", 0L).asInstanceOf[Long] / statistics.getOrElseUpdate("frontier_pages_completed", 1).asInstanceOf[Int]
                if (statistics.getOrElse("frontier_pages_completed", 1).asInstanceOf[Int].toDouble != 0)
                    statistics("frontier_percent_complete") = statistics.getOrElse("frontier_pages_completed", 0).asInstanceOf[Int].toDouble / (statistics.getOrElse("frontier_pages_completed", 1).asInstanceOf[Int].toDouble + statistics.getOrElse("frontier_pages", 1).asInstanceOf[Int].toDouble)
            }
            case "browser" => {
                statistics("browsers_active") = statistics.getOrElseUpdate("browsers_active", 0).asInstanceOf[Int] + data.getOrElse("browser_increment", 0).asInstanceOf[Int] - data.getOrElse("browser_decrement", 0).asInstanceOf[Int]
            }
            case "throttle" => {
                if (data.contains("domain")) {
                    val domain = data("domain").asInstanceOf[String].replace(".", "_")
                    statistics(s"page_throttling_${domain}_throttle_frequency") = data.getOrElse("throttleFrequency", statistics.getOrElseUpdate(s"page_throttling_${domain}_throttle_frequency", 0).asInstanceOf[Int]).asInstanceOf[Int]
                    statistics(s"page_throttling_${domain}_throttle_time_rate") = data.getOrElse("throttleRate", statistics.getOrElseUpdate(s"page_throttling_${domain}_throttle_time_rate", 0L).asInstanceOf[Long]).asInstanceOf[Long]
                    statistics(s"page_throttling_${domain}_downloads_within_rate") = data.getOrElse("downloadsWithinRate", statistics.getOrElseUpdate(s"page_throttling_${domain}_downloads_within_rate", 0).asInstanceOf[Int]).asInstanceOf[Int]
                    statistics(s"page_throttling_${domain}_downloads_time_rate") = data.getOrElse("timePassed", statistics.getOrElseUpdate(s"page_throttling_${domain}_downloads_time_rate", 0L).asInstanceOf[Long]).asInstanceOf[Long]
                    statistics(s"page_throttling_${domain}_wait_counter") = data.getOrElse("waitCounter", statistics.getOrElseUpdate(s"page_throttling_${domain}_wait_counter", 0).asInstanceOf[Int]).asInstanceOf[Int]
                    statistics(s"page_throttling_${domain}_wait_current_count") = data.getOrElse("currentWait", statistics.getOrElseUpdate(s"page_throttling_${domain}_wait_current_count", 0).asInstanceOf[Int]).asInstanceOf[Int]
                    statistics(s"page_throttling_${domain}_change_tighten_counter") = data.getOrElse("changeTightCounter", statistics.getOrElseUpdate(s"page_throttling_${domain}_change_tighten_counter", 0).asInstanceOf[Int]).asInstanceOf[Int]
                    statistics(s"page_throttling_${domain}_change_loosen_counter") = data.getOrElse("changeLooseCounter", statistics.getOrElseUpdate(s"page_throttling_${domain}_change_loosen_counter", 0).asInstanceOf[Int]).asInstanceOf[Int]
                    statistics(s"page_throttling_${domain}_seeder_count") = data.getOrElse("seederCount", statistics.getOrElseUpdate(s"page_throttling_${domain}_seeder_count", 0).asInstanceOf[Int]).asInstanceOf[Int]
                }
            }
            case _ => {}
        }
    }

    def memoryStatistics = {
        statistics("memory_heap_committed") = ManagementFactory.getMemoryMXBean.getHeapMemoryUsage.getCommitted
        statistics("memory_heap_usage") = ManagementFactory.getMemoryMXBean.getHeapMemoryUsage.getUsed
        statistics("memory_non_heap_committed") = ManagementFactory.getMemoryMXBean.getNonHeapMemoryUsage.getCommitted
        statistics("memory_non_heap_usage") = ManagementFactory.getMemoryMXBean.getNonHeapMemoryUsage.getUsed
        statistics("process_thread_count") = ManagementFactory.getThreadMXBean.getThreadCount
        statistics("process_thread_peak_count") = ManagementFactory.getThreadMXBean.getPeakThreadCount
        statistics("system_load_average") = ManagementFactory.getOperatingSystemMXBean.getSystemLoadAverage
        statistics("system_processor_count") = ManagementFactory.getOperatingSystemMXBean.getAvailableProcessors
        statistics("system_architecture_name") = ManagementFactory.getOperatingSystemMXBean.getArch
        statistics("system_architecture_version") = ManagementFactory.getOperatingSystemMXBean.getVersion
        statistics("system_java_vm_version") = ManagementFactory.getRuntimeMXBean.getVmVersion
    }
}
