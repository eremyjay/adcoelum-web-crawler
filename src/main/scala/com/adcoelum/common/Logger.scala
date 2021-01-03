package com.adcoelum.common

import ch.qos.logback.classic.filter.ThresholdFilter
import ch.qos.logback.classic.html.HTMLLayout
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, LoggerContext, PatternLayout}
import ch.qos.logback.core.encoder.LayoutWrappingEncoder
import ch.qos.logback.core.html.{IThrowableRenderer, NOPThrowableRenderer}
import ch.qos.logback.core.rolling.{RollingFileAppender, SizeAndTimeBasedRollingPolicy}
import ch.qos.logback.core.util.FileSize
import ch.qos.logback.core.{ConsoleAppender, LayoutBase}
import org.slf4j.LoggerFactory
import com.github.alexvictoor.weblogback.BrowserConsoleAppender


/**
  * The logger object provides a comprehensive logger with colour coding, log levels and logging intensity settings
  */
object Logger {
    // Define colour options
    val NORMAL = 0
    val BRIGHT = 1
    val FOREGROUND_BLACK = 30
    val FOREGROUND_RED = 31
    val FOREGROUND_GREEN = 32
    val FOREGROUND_YELLOW = 33
    val FOREGROUND_BLUE = 34
    val FOREGROUND_MAGENTA = 35
    val FOREGROUND_CYAN = 36
    val FOREGROUND_LIGHT_GRAY = 37
    val FOREGROUND_DARK_GRAY = 90
    val FOREGROUND_LIGHT_RED = 91
    val FOREGROUND_LIGHT_GREEN = 92
    val FOREGROUND_LIGHT_YELLOW = 93
    val FOREGROUND_LIGHT_BLUE = 94
    val FOREGROUND_LIGHT_MAGENTA = 95
    val FOREGROUND_LIGHT_CYAN = 96
    val FOREGROUND_WHITE = 97


    // Establish colour information
    val PREFIX = "\u001b["
    val SUFFIX = "m"
    val SEPARATOR = ""
    val END_COLOUR = PREFIX + SUFFIX

    // Define log level colours
    val ERROR_COLOUR = PREFIX + NORMAL + SEPARATOR + FOREGROUND_RED + SUFFIX
    val WARN_COLOUR = PREFIX + NORMAL + SEPARATOR + FOREGROUND_YELLOW + SUFFIX
    val INFO_COLOUR = PREFIX + NORMAL+ SEPARATOR + FOREGROUND_GREEN + SUFFIX
    val DEBUG_COLOUR = PREFIX + NORMAL + SEPARATOR + FOREGROUND_CYAN + SUFFIX
    val TRACE_COLOUR = PREFIX + NORMAL + SEPARATOR + FOREGROUND_LIGHT_MAGENTA + SUFFIX

    // Determine whether to output to console
    val console = (Configuration.config \ "logging" \ "console").as[Boolean]

    // Define levels
    val ERROR = "error"
    val WARN = "warn"
    val INFO = "info"
    val DEBUG = "debug"
    val TRACE = "trace"
    val ALL = "all"
    val OFF = "off"

    val ERROR_VALUE = 1
    val WARN_VALUE = 2
    val INFO_VALUE = 3
    val DEBUG_VALUE = 4
    val TRACE_VALUE = 5
    val ALL_VALUE = 6
    val OFF_VALUE = 0

    // Set log level to determine deepest level of logging to perform
    val definedLevel = (Configuration.config \ "logging" \ "level").as[String]
    val logLevel = definedLevel match {
        case ERROR => Level.ERROR
        case WARN => Level.WARN
        case INFO => Level.INFO
        case DEBUG => Level.DEBUG
        case TRACE => Level.TRACE
        case OFF => Level.OFF
        case ALL => Level.ALL
        case _ => Level.INFO
    }
    val logLevelValue = definedLevel match {
        case ERROR => ERROR_VALUE
        case WARN => WARN_VALUE
        case INFO => INFO_VALUE
        case DEBUG => DEBUG_VALUE
        case TRACE => TRACE_VALUE
        case OFF => OFF_VALUE
        case ALL => ALL_VALUE
    }

    // Set the default log level
    val defaultLevel = (Configuration.config \ "logging" \ "defaultLevel").as[String]
    val defaultLogLevel = defaultLevel match {
        case ERROR => Level.ERROR
        case WARN => Level.WARN
        case INFO => Level.INFO
        case DEBUG => Level.DEBUG
        case TRACE => Level.TRACE
        case OFF => Level.OFF
        case ALL => Level.ALL
        case _ => Level.INFO
    }
    val defaultLogLevelValue = defaultLevel match {
        case ERROR => ERROR_VALUE
        case WARN => WARN_VALUE
        case INFO => INFO_VALUE
        case DEBUG => DEBUG_VALUE
        case TRACE => TRACE_VALUE
        case OFF => OFF_VALUE
        case ALL => ALL_VALUE
    }

    // Set web driver log level
    val webDriverLevel = (Configuration.config \ "logging" \ "browser").as[String]
    val webDriverLogLevel = webDriverLevel match {
        case ERROR => Level.ERROR
        case WARN => Level.WARN
        case INFO => Level.INFO
        case DEBUG => Level.DEBUG
        case TRACE => Level.TRACE
        case OFF => Level.OFF
        case ALL => Level.ALL
        case _ => Level.INFO
    }
    val webDriverLogLevelStd = webDriverLevel match {
        case ERROR => java.util.logging.Level.SEVERE
        case WARN => java.util.logging.Level.WARNING
        case INFO => java.util.logging.Level.INFO
        case DEBUG => java.util.logging.Level.FINE
        case TRACE => java.util.logging.Level.FINEST
        case OFF => java.util.logging.Level.OFF
        case ALL => java.util.logging.Level.ALL
        case _ => java.util.logging.Level.INFO
    }
    val webDriverLogLevelValue = webDriverLevel match {
        case ERROR => ERROR_VALUE
        case WARN => WARN_VALUE
        case INFO => INFO_VALUE
        case DEBUG => DEBUG_VALUE
        case TRACE => TRACE_VALUE
        case OFF => OFF_VALUE
        case ALL => ALL_VALUE
    }

    // Setup web console parameters
    val webConsoleEnabled = (Configuration.config \ "logging" \ "webConsole").as[Boolean]
    val webConsoleStartPort = (Configuration.config \ "logging" \ "webStartPort").as[Int]
    val webConsoleBuffer = (Configuration.config \ "logging" \ "webBuffer").as[Int]

    // Obtain intensity level from configuration
    val definedIntensity = (Configuration.config \ "logging" \ "intensity").as[Int]

    // Obtain whether should include all intensity levels up to specified
    val fixedLevel = (Configuration.config \ "logging" \ "fixedLevel").as[Boolean]

    // Obtain whether should include all intensity levels up to specified
    val fixedIntensity = (Configuration.config \ "logging" \ "fixedIntensity").as[Boolean]

    // Determine whether to output stack trace
    val stackTrace = (Configuration.config \ "logging" \ "stackTrace").as[Boolean]

    // File logging variables
    var logfilePath = {
        val path = (Configuration.config \ "logging" \ "path").as[String]
        if (path.startsWith("/"))
            path
        else if (path.startsWith("./"))
            System.getProperty("user.dir") + path.drop(1)
        else
            System.getProperty("user.dir") + "/" + path
    }
    val appendToFile = (Configuration.config \ "logging" \ "append").as[Boolean]

    // Define intensities
    val BASIC_INTENSITY = 0
    val VERBOSE_INTENSITY = 1
    val MEGA_VERBOSE_INTENSITY = 2
    val SUPER_VERBOSE_INTENSITY = 3
    val HYPER_VERBOSE_INTENSITY = 4
    val ULTRA_VERBOSE_INTENSITY = 5
    val VERBOSE_VERBOSE_INTENSITY = 6
    val MEGA_VERBOSE_VERBOSE_INTENSITY = 7
    val SUPER_VERBOSE_VERBOSE_INTENSITY = 8
    val HYPER_VERBOSE_VERBOSE_INTENSITY = 9
    val ULTRA_VERBOSE_VERBOSE_INTENSITY = 10

    // Set up default logger
    val root = LoggerFactory.getLogger("root").asInstanceOf[ch.qos.logback.classic.Logger]
    root.setLevel(defaultLogLevel)

    // Set up logger
    val logger: ch.qos.logback.classic.Logger = LoggerFactory.getLogger("searchBot").asInstanceOf[ch.qos.logback.classic.Logger]

    // Obtain logging context
    val context: LoggerContext = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    context.reset

    // Set logger details
    logger.setLevel(logLevel)
    logger.setAdditive(false)

    // Determine logging format
    val maxLogDays = (Configuration.config \ "logging" \ "maxLogDays").as[Int]
    val maxLogSize = (Configuration.config \ "logging" \ "maxLogSize").as[String]
    val maxLogTotalSize = (Configuration.config \ "logging" \ "maxLogTotalSize").as[String]

    val loggingStandardFormat = (Configuration.config \ "logging" \ "formatStandard").as[String]
    val loggingHtmlFormat = (Configuration.config \ "logging" \ "formatHTML").as[String]
    val formatAsHtml = (Configuration.config \ "logging" \ "asHTML").as[Boolean]

    // Set layout
    val htmlLayoutErrors = new HTMLLayout
    htmlLayoutErrors.setContext(context)
    htmlLayoutErrors.setPattern(loggingHtmlFormat)
    htmlLayoutErrors.start

    val htmlLayoutNoErrors = new HTMLLayout
    htmlLayoutNoErrors.setContext(context)
    htmlLayoutNoErrors.setPattern(loggingHtmlFormat)
    htmlLayoutNoErrors.setThrowableRenderer(new NOPThrowableRenderer().asInstanceOf[IThrowableRenderer[ILoggingEvent]])
    htmlLayoutNoErrors.start

    val standardLayoutErrors = new PatternLayout
    standardLayoutErrors.setContext(context)
    standardLayoutErrors.setPattern(loggingStandardFormat)
    standardLayoutErrors.start

    val standardLayoutNoErrors = new PatternLayout
    standardLayoutNoErrors.setContext(context)
    standardLayoutNoErrors.setPattern(loggingStandardFormat + "%nopex")
    standardLayoutNoErrors.start


    // Specify logging instances
    if (defaultLevel != OFF) {
        val consoleLog: ConsoleAppender[ILoggingEvent] = new ConsoleAppender[ILoggingEvent]

        // Set encoder for logging
        val encoder: LayoutWrappingEncoder[ILoggingEvent] = new LayoutWrappingEncoder[ILoggingEvent]
        encoder.setContext(context)
        encoder.setLayout(standardLayoutNoErrors)
        encoder.start

        val consoleDefaultFilter = new ThresholdFilter
        consoleDefaultFilter.setLevel(defaultLevel)
        consoleLog.addFilter(consoleDefaultFilter)

        consoleLog.setName("console")
        consoleLog.setContext(context)
        consoleLog.setTarget("System.err")
        consoleLog.setEncoder(encoder)
        consoleLog.start
        root.addAppender(consoleLog)
    }


    if (logLevelValue >= TRACE_VALUE) {
        val traceFile: RollingFileAppender[ILoggingEvent] = new RollingFileAppender[ILoggingEvent]
        traceFile.setContext(context)
        traceFile.setName("trace")
        traceFile.setFile(logfilePath + "/" + "trace.log")
        val traceFilter = new ThresholdFilter
        traceFilter.setLevel(TRACE)
        traceFile.addFilter(traceFilter)

        val sizeAndTimeBasedRollingPolicy: SizeAndTimeBasedRollingPolicy[ILoggingEvent] = new SizeAndTimeBasedRollingPolicy[ILoggingEvent]
        sizeAndTimeBasedRollingPolicy.setContext(context)
        sizeAndTimeBasedRollingPolicy.setParent(traceFile)
        sizeAndTimeBasedRollingPolicy.setMaxHistory(maxLogDays)
        sizeAndTimeBasedRollingPolicy.setMaxFileSize(FileSize.valueOf(maxLogSize))
        sizeAndTimeBasedRollingPolicy.setTotalSizeCap(FileSize.valueOf(maxLogTotalSize))
        sizeAndTimeBasedRollingPolicy.setFileNamePattern(logfilePath + "/" + "trace.%d.%i.log")
        sizeAndTimeBasedRollingPolicy.start
        traceFile.setRollingPolicy(sizeAndTimeBasedRollingPolicy)
        traceFile.setTriggeringPolicy(sizeAndTimeBasedRollingPolicy)

        // Set encoder for logging
        val encoder: LayoutWrappingEncoder[ILoggingEvent] = new LayoutWrappingEncoder[ILoggingEvent]
        encoder.setContext(context)

        if (formatAsHtml)
            encoder.setLayout(htmlLayoutNoErrors)
        else
            encoder.setLayout(standardLayoutNoErrors)

        encoder.start

        traceFile.setEncoder(encoder)
        traceFile.setAppend(appendToFile)
        traceFile.start
        logger.addAppender(traceFile)
    }

    if (logLevelValue >= DEBUG_VALUE) {
        val debugFile: RollingFileAppender[ILoggingEvent] = new RollingFileAppender[ILoggingEvent]
        debugFile.setContext(context)
        debugFile.setName("debug")
        debugFile.setFile(logfilePath + "/" + "debug.log")
        val debugFilter = new ThresholdFilter
        debugFilter.setLevel(DEBUG)
        debugFilter.start
        debugFile.addFilter(debugFilter)

        val sizeAndTimeBasedRollingPolicy: SizeAndTimeBasedRollingPolicy[ILoggingEvent] = new SizeAndTimeBasedRollingPolicy[ILoggingEvent]
        sizeAndTimeBasedRollingPolicy.setContext(context)
        sizeAndTimeBasedRollingPolicy.setParent(debugFile)
        sizeAndTimeBasedRollingPolicy.setMaxHistory(maxLogDays)
        sizeAndTimeBasedRollingPolicy.setMaxFileSize(FileSize.valueOf(maxLogSize))
        sizeAndTimeBasedRollingPolicy.setTotalSizeCap(FileSize.valueOf(maxLogTotalSize))
        sizeAndTimeBasedRollingPolicy.setFileNamePattern(logfilePath + "/" + "debug.%d.%i.log")
        sizeAndTimeBasedRollingPolicy.start
        debugFile.setRollingPolicy(sizeAndTimeBasedRollingPolicy)
        debugFile.setTriggeringPolicy(sizeAndTimeBasedRollingPolicy)

        // Set encoder for logging
        val encoder: LayoutWrappingEncoder[ILoggingEvent] = new LayoutWrappingEncoder[ILoggingEvent]
        encoder.setContext(context)

        if (formatAsHtml)
            encoder.setLayout(htmlLayoutNoErrors)
        else
            encoder.setLayout(standardLayoutNoErrors)

        encoder.start

        debugFile.setEncoder(encoder)
        debugFile.setAppend(appendToFile)
        debugFile.start
        logger.addAppender(debugFile)
    }

    if (logLevelValue >= INFO_VALUE) {
        val infoFile: RollingFileAppender[ILoggingEvent] = new RollingFileAppender[ILoggingEvent]
        infoFile.setContext(context)
        infoFile.setName("info")
        infoFile.setFile(logfilePath + "/" + "info.log")
        val infoFilter = new ThresholdFilter
        infoFilter.setLevel(INFO)
        infoFilter.start
        infoFile.addFilter(infoFilter)

        val sizeAndTimeBasedRollingPolicy: SizeAndTimeBasedRollingPolicy[ILoggingEvent] = new SizeAndTimeBasedRollingPolicy[ILoggingEvent]
        sizeAndTimeBasedRollingPolicy.setContext(context)
        sizeAndTimeBasedRollingPolicy.setParent(infoFile)
        sizeAndTimeBasedRollingPolicy.setMaxHistory(maxLogDays)
        sizeAndTimeBasedRollingPolicy.setMaxFileSize(FileSize.valueOf(maxLogSize))
        sizeAndTimeBasedRollingPolicy.setTotalSizeCap(FileSize.valueOf(maxLogTotalSize))
        sizeAndTimeBasedRollingPolicy.setFileNamePattern(logfilePath + "/" + "info.%d.%i.log")
        sizeAndTimeBasedRollingPolicy.start
        infoFile.setRollingPolicy(sizeAndTimeBasedRollingPolicy)
        infoFile.setTriggeringPolicy(sizeAndTimeBasedRollingPolicy)

        // Set encoder for logging
        val encoder: LayoutWrappingEncoder[ILoggingEvent] = new LayoutWrappingEncoder[ILoggingEvent]
        encoder.setContext(context)

        if (formatAsHtml)
            encoder.setLayout(htmlLayoutNoErrors)
        else
            encoder.setLayout(standardLayoutNoErrors)

        encoder.start

        infoFile.setEncoder(encoder)
        infoFile.setAppend(appendToFile)
        infoFile.start
        logger.addAppender(infoFile)
    }

    if (logLevelValue >= WARN_VALUE) {
        val warnFile: RollingFileAppender[ILoggingEvent] = new RollingFileAppender[ILoggingEvent]
        warnFile.setContext(context)
        warnFile.setName("warn")
        warnFile.setFile(logfilePath + "/" + "warn.log")
        val warnFilter = new ThresholdFilter
        warnFilter.setLevel(WARN)
        warnFilter.start
        warnFile.addFilter(warnFilter)

        val sizeAndTimeBasedRollingPolicy: SizeAndTimeBasedRollingPolicy[ILoggingEvent] = new SizeAndTimeBasedRollingPolicy[ILoggingEvent]
        sizeAndTimeBasedRollingPolicy.setContext(context)
        sizeAndTimeBasedRollingPolicy.setParent(warnFile)
        sizeAndTimeBasedRollingPolicy.setMaxHistory(maxLogDays)
        sizeAndTimeBasedRollingPolicy.setMaxFileSize(FileSize.valueOf(maxLogSize))
        sizeAndTimeBasedRollingPolicy.setTotalSizeCap(FileSize.valueOf(maxLogTotalSize))
        sizeAndTimeBasedRollingPolicy.setFileNamePattern(logfilePath + "/" + "warn.%d.%i.log")
        sizeAndTimeBasedRollingPolicy.start
        warnFile.setRollingPolicy(sizeAndTimeBasedRollingPolicy)
        warnFile.setTriggeringPolicy(sizeAndTimeBasedRollingPolicy)

        // Set encoder for logging
        val encoder: LayoutWrappingEncoder[ILoggingEvent] = new LayoutWrappingEncoder[ILoggingEvent]
        encoder.setContext(context)

        if (formatAsHtml)
            encoder.setLayout(htmlLayoutErrors)
        else
            encoder.setLayout(standardLayoutErrors)

        encoder.start

        warnFile.setEncoder(encoder)
        warnFile.setAppend(appendToFile)
        warnFile.start
        logger.addAppender(warnFile)
    }

    if (logLevelValue >= ERROR_VALUE) {
        val errorFile: RollingFileAppender[ILoggingEvent] = new RollingFileAppender[ILoggingEvent]
        errorFile.setContext(context)
        errorFile.setName("error")
        errorFile.setFile(logfilePath + "/" + "error.log")
        val errorFilter = new ThresholdFilter
        errorFilter.setLevel(ERROR)
        errorFilter.start
        errorFile.addFilter(errorFilter)

        val sizeAndTimeBasedRollingPolicy: SizeAndTimeBasedRollingPolicy[ILoggingEvent] = new SizeAndTimeBasedRollingPolicy[ILoggingEvent]
        sizeAndTimeBasedRollingPolicy.setContext(context)
        sizeAndTimeBasedRollingPolicy.setParent(errorFile)
        sizeAndTimeBasedRollingPolicy.setMaxHistory(maxLogDays)
        sizeAndTimeBasedRollingPolicy.setMaxFileSize(FileSize.valueOf(maxLogSize))
        sizeAndTimeBasedRollingPolicy.setTotalSizeCap(FileSize.valueOf(maxLogTotalSize))
        sizeAndTimeBasedRollingPolicy.setFileNamePattern(logfilePath + "/" + "error.%d.%i.log")
        sizeAndTimeBasedRollingPolicy.start
        errorFile.setRollingPolicy(sizeAndTimeBasedRollingPolicy)
        errorFile.setTriggeringPolicy(sizeAndTimeBasedRollingPolicy)

        // Set encoder for logging
        val encoder: LayoutWrappingEncoder[ILoggingEvent] = new LayoutWrappingEncoder[ILoggingEvent]
        encoder.setContext(context)

        if (formatAsHtml)
            encoder.setLayout(htmlLayoutErrors)
        else
            encoder.setLayout(standardLayoutErrors)

        encoder.start

        errorFile.setEncoder(encoder)
        errorFile.setAppend(appendToFile)
        errorFile.start
        logger.addAppender(errorFile)
    }


    if (webConsoleEnabled) {
        val webConsoleLog: BrowserConsoleAppender[ILoggingEvent] = new BrowserConsoleAppender[ILoggingEvent]
        webConsoleLog.setContext(context)
        webConsoleLog.setName("web")
        val webConsoleFilter = new ThresholdFilter
        webConsoleFilter.setLevel(definedLevel)
        webConsoleFilter.start
        webConsoleLog.addFilter(webConsoleFilter)

        // Set encoder for logging
        val encoder: LayoutWrappingEncoder[ILoggingEvent] = new LayoutWrappingEncoder[ILoggingEvent]
        encoder.setContext(context)

        encoder.setLayout(standardLayoutErrors)
        encoder.start

        webConsoleLog.setEncoder(encoder)

        webConsoleLog.setActive(webConsoleEnabled)
        webConsoleLog.setBuffer(webConsoleBuffer)
        webConsoleLog.setPort({
            var portToUse = webConsoleStartPort

            while (Tools.isPortInUse(portToUse, Configuration.host))
                portToUse += 1

            portToUse
        })
        webConsoleLog.start
        logger.addAppender(webConsoleLog)
    }


    /**
      * Log an info message
      *
      * @param message
      * @param intensity
      */
    def info(message: String, intensity: Integer = BASIC_INTENSITY) = {
        if ((fixedLevel && logLevelValue == INFO_VALUE) || !fixedLevel) {
            if ((fixedIntensity && intensity == definedIntensity) || (!fixedIntensity && intensity <= definedIntensity)) {
                if (console)
                    System.err.println(INFO_COLOUR + message + END_COLOUR)

                logger.info(message)
            }
        }
    }

    /**
      * Log a warn message
      *
      * @param message
      * @param intensity
      */
    def warn(message: String, intensity: Integer = BASIC_INTENSITY, error: Throwable) = {
        if ((fixedLevel && logLevelValue == WARN_VALUE) || !fixedLevel) {
            if ((fixedIntensity && intensity == definedIntensity) || (!fixedIntensity && intensity <= definedIntensity)) {
                if (console)
                    System.err.println(WARN_COLOUR + message + END_COLOUR)

                logger.warn(message, error)

                if (stackTrace) {
                    error.printStackTrace(System.err)
                }
            }
        }
    }

    /**
      * Log an error message
      *
      * @param message
      * @param intensity
      */
    def error(message: String, intensity: Integer = BASIC_INTENSITY, error: Throwable) = {
        if ((fixedLevel && logLevelValue == ERROR_VALUE) || !fixedLevel) {
            if ((fixedIntensity && intensity == definedIntensity) || (!fixedIntensity && intensity <= definedIntensity)) {
                if (console)
                    System.err.println(ERROR_COLOUR + message + END_COLOUR)

                logger.error(message, error)

                if (stackTrace) {
                    error.printStackTrace(System.err)
                }
            }
        }
    }

    /**
      * Log a debug message
      *
      * @param message
      * @param intensity
      */
    def debug(message: String, intensity: Integer = BASIC_INTENSITY) = {
        if ((fixedLevel && logLevelValue == DEBUG_VALUE) || !fixedLevel) {
            if ((fixedIntensity && intensity == definedIntensity) || (!fixedIntensity && intensity <= definedIntensity)) {
                if (console)
                    System.err.println(DEBUG_COLOUR + message + END_COLOUR)

                logger.debug(message)
            }
        }
    }

    /**
      * Log a trace message
      *
      * @param message
      * @param intensity
      */
    def trace(message: String, intensity: Integer = BASIC_INTENSITY) = {
        if ((fixedLevel && logLevelValue == TRACE_VALUE) || !fixedLevel) {
            if ((fixedIntensity && intensity == definedIntensity) || (!fixedIntensity && intensity <= definedIntensity)) {
                if (console)
                    System.err.println(TRACE_COLOUR + message + END_COLOUR)

                logger.trace(message)
            }
        }
    }
}

class LoggingLayout extends LayoutBase[ILoggingEvent] {
    override def doLayout(event: ILoggingEvent): String = {
        ""
    }
}


