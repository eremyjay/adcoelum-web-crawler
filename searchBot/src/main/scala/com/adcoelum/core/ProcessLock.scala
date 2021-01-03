package com.adcoelum.core

import com.adcoelum.common.Logger
import java.io.{BufferedReader, File, FileReader, PrintWriter}

import com.adcoelum.common.Configuration

import sys.process._
import scala.language.postfixOps

/**
  * Created by jeremy on 3/01/2017.
  */
object ProcessLock {
    val path = (Configuration.config \ "process" \ "path").as[String]
    val file = (Configuration.config \ "process" \ "file").as[String]
    val fullPath = path + "/" + file

    def anyExistingProcesses: Boolean = {
        try {
            Logger.info("Determining whether any existing processes are running")
            val lockFile = new File(fullPath)
            if (lockFile.exists && !lockFile.isDirectory) {
                val reader = new BufferedReader(new FileReader(fullPath))
                val pid = reader.readLine
                reader.close

                if (pid != null) {
                    val currentProcess = {
                        try {
                            (List("ps", "-p", s"$pid", "-o", "pid=") !!).trim.toInt
                        } catch {
                            case _: Throwable => false
                        }
                    }
                    val exists = pid.toInt == currentProcess

                    if (!exists) {
                        lockFile.delete
                        false
                    }
                    else {
                        Logger.info("Existing process found with process ID")
                        true
                    }
                }
                else
                    false
            }
            else
                false
        }
        catch {
            case e: Throwable => {
                Logger.warn("Unable to determine whether there is an existing process", error = e)
                false
            }
        }
    }

    def createProcessLock(pid: Int): Boolean = {
        try {
            Logger.info(s"Writing process lock file with process id: ${pid}")
            val writer = new PrintWriter(fullPath)
            writer.println(pid.toString)
            writer.close
            true
        }
        catch {
            case e: Throwable => {
                Logger.error("Unable to create process lock file", error = e)
                false
            }
        }
    }
}
