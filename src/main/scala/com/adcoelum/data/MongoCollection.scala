package com.adcoelum.data

import java.util.concurrent.TimeoutException

import com.adcoelum.common.{Configuration, Logger, Tools}
import reactivemongo.api._
import reactivemongo.api.commands.AggregationFramework
import reactivemongo.api.indexes._
import reactivemongo.bson._
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.commands.bson.BSONAggregationFramework
import reactivemongo.bson.Subtype.GenericBinarySubtype
import reactivemongo.core.nodeset.Authenticate

import scala.collection.mutable
import scala.util.{Failure, Random, Success}
import scala.collection.mutable.ListBuffer
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.concurrent.ExecutionContext.Implicits.global


class MongoCollection extends CollectionCore {
    // Set connection variables for collections
    val servers = Configuration.collectionsStore("servers").asInstanceOf[List[String]]
    val dbName = Configuration.collectionsStore("dbname").asInstanceOf[String]
    val username = Configuration.collectionsStore("username").asInstanceOf[String]
    val password = Configuration.collectionsStore("password").asInstanceOf[String]

    // Get driver and set options for the collection connection
    var connectionInfo: MongoConnection = _
    var failOverStrategy: FailoverStrategy = _
    var database: DefaultDB = _
    var collection: BSONCollection = _
    var connection: CollectionConnection[DefaultDB] = _ // Historically created object here

    var resultDelay = 1 second
    var connected = false

    def connect(collectionName: String) = {
        var retryCount = 0
        var success = false
        connected = false

        while (!success && retryCount < defaultRetries) {
            try {
                val driver = new reactivemongo.api.MongoDriver
                val options = MongoConnectionOptions(authMode = ScramSha1Authentication,
                    nbChannelsPerNode = (Configuration.config \ "collections" \ "channelsPerNode").as[Int],
                    connectTimeoutMS = (Configuration.config \ "collections" \ "connectTimeout").as[Int] * 1000,
                    tcpNoDelay = (Configuration.config \ "collections" \ "tcpNoDelay").as[Boolean],
                    keepAlive = (Configuration.config \ "collections" \ "keepAlive").as[Boolean])

                // Set the credentials and connection information
                val credentials = List(Authenticate(dbName, username, password))
                connectionInfo = driver.connection(servers, options = options, authentications = credentials)

                // Determine a failover strategy for connection attempts
                failOverStrategy = FailoverStrategy(
                    initialDelay = (Configuration.config \ "collections" \ "failOverDelay").as[Int] seconds,
                    retries = (Configuration.config \ "collections" \ "failOverRetries").as[Int],
                    delayFactor = attemptNumber => 1 + attemptNumber * 0.5)

                // Connect to the collection dataset
                val databaseConnectAttempt = connectionInfo.database(dbName, failOverStrategy)
                Await.result(databaseConnectAttempt, defaultConnectWait)

                databaseConnectAttempt onComplete {
                    case Failure(e) => {
                        Logger.error(s"Failed to connect to collection database: ${e.getMessage}", error = e)
                    }
                    case Success(db) => {
                        database = db
                        connection = new CollectionConnection[DefaultDB]
                        connection.set(database)
                        collection = database.collection(collectionName, failOverStrategy)
                        success = true
                        connected = true
                        Logger.trace(s"Connected to collection database successfully: ${db.name}", Logger.HYPER_VERBOSE_INTENSITY)
                    }
                }
            }
            catch {
                case e: TimeoutException => {
                    retryCount += 1
                    Logger.warn(s"Connection attempt failed due to timeout - attempting retry ${retryCount}: ${e.getMessage}", error = e)
                }
                case e: Throwable => {
                    retryCount += 1
                    Logger.warn(s"Connection attempt failed - attempting retry ${retryCount}: ${e.getMessage}", error = e)
                    Tools.sleep(defaultWaitTime)
                }
            }
        }
    }

    def setConnection(connectionMap: Map[String, Any]) = {
        var retryCount = 0
        var success = false
        connected = false

        while (!success && retryCount < defaultRetries) {
            try {
                if (connectionMap.contains("connectionInfo"))
                    connectionInfo = connectionMap("connectionInfo").asInstanceOf[MongoConnection]
                if (connectionMap.contains("failOverStrategy"))
                    failOverStrategy = connectionMap("failOverStrategy").asInstanceOf[FailoverStrategy]

                // Connect to the collection dataset
                val databaseConnectAttempt = connectionInfo.database(dbName, failOverStrategy)
                Await.result(databaseConnectAttempt, defaultConnectWait)

                databaseConnectAttempt onComplete {
                    case Failure(e) => {
                        Logger.error(s"Failed to connect to collection database: ${e.getMessage}", error = e)
                    }
                    case Success(database) => {
                        if (connectionMap.contains("collection"))
                            collection = database.collection(connectionMap("collection").asInstanceOf[String], failOverStrategy)
                        connection = new CollectionConnection[DefaultDB]
                        connection.set(database)
                        success = true
                        connected = true
                        Logger.trace(s"Connected to collection database successfully: ${collection.name}", Logger.HYPER_VERBOSE_INTENSITY)
                    }
                }
            }
            catch {
                case e: TimeoutException => {
                    retryCount += 1
                    Logger.warn(s"Connection attempt failed due to timeout - attempting retry ${retryCount}: ${e.getMessage}", error = e)
                }
                case e: Throwable => {
                    retryCount += 1
                    Logger.warn(s"Connection attempt failed - attempting retry ${retryCount}: ${e.getMessage}", error = e)
                    Tools.sleep(defaultWaitTime)
                }
            }
        }
    }

    def getConnection[X]: CollectionConnection[X] = {
        connection.asInstanceOf[CollectionConnection[X]]
    }

    def setCollection(collectionName: String) = {
        collection = database[BSONCollection](collectionName)
    }

    def copyConnection(collectionName: String): MongoCollection = {
        val newCollection = new MongoCollection
        newCollection.database = database
        newCollection.connectionInfo = connectionInfo
        newCollection.failOverStrategy = failOverStrategy
        newCollection.connection = connection
        newCollection.connected = connected
        newCollection.collection = database[BSONCollection](collectionName)
        newCollection
    }

    def create(data: Map[String, Any], callback: Function1[Boolean, Any] = (status) => {}, instruction: String = "", instructionBased: Boolean = false, wait: Boolean = defaultWriteWait, waitTime: FiniteDuration = defaultWaitTime, retries: Int = defaultRetries) = {
        var retryCount = 0
        var success = false

        while (!success && retryCount < retries) {
            try {
                if (!connected)
                    throw new Exception("Connection not yet fully established")

                // Create BSON document for writing
                val bson = Mongo.mongoConvert(data)
                var bsonData: BSONDocument = BSONDocument()

                // Set data points
                bson.foreach { case (key: String, value: BSONValue) =>
                    bsonData = bsonData ++ BSONDocument(key -> value)
                }

                // Set query information against which to set data
                val modifier = bsonData

                // Perform the data write
                val result = collection.insert(modifier)

                result onComplete {
                    case Failure(e) => {
                        Logger.error(s"Failed to perform create with error ${e.getMessage}", error = e)
                        callback(false)
                    }
                    case Success(value) => {
                        Logger.trace(s"Create performed successfully: ${value}", Logger.HYPER_VERBOSE_INTENSITY)
                        callback(true)
                    }
                }

                if (wait) {
                    Await.result(result, waitTime)
                    Tools.sleep(resultDelay)
                }

                success = true
            }
            catch {
                case e: TimeoutException => {
                    retryCount += 1
                    Logger.warn(s"Create on collection failed due to timeout - attempting retry ${retryCount}: ${e.getMessage}", error = e)
                }
                case e: Throwable => {
                    retryCount += 1
                    Logger.warn(s"Create on collection failed - attempting retry ${retryCount}: ${e.getMessage}", error = e)
                    Tools.sleep(defaultWaitTime)
                }
            }
        }
    }

    def update(data: Map[String, Any], filter: Map[String, Any], callback: Function1[Boolean, Any] = (status) => {}, instruction: String = "$set", instructionBased: Boolean = false, wait: Boolean = defaultWriteWait, waitTime: FiniteDuration = defaultWaitTime, retries: Int = defaultRetries) = {
        var retryCount = 0
        var success = false

        while (!success && retryCount < retries) {
            try {
                if (!connected)
                    throw new Exception("Connection not yet fully established")

                // Create BSON document for writing
                val bson = Mongo.mongoConvert(data)
                var bsonData: BSONDocument = BSONDocument()

                // Set data points
                bson.foreach { case (key: String, value: BSONValue) =>
                    bsonData = bsonData ++ BSONDocument(key -> value)
                }

                // Setup filter for setting selector
                val select = Mongo.mongoConvert(filter)
                var selectData: BSONDocument = BSONDocument()

                // Set data points
                select.foreach { case (key: String, value: BSONValue) =>
                    selectData = selectData ++ BSONDocument(key -> value)
                }

                // Set query information against which to set data
                val selector = selectData
                val modifier = {
                    if (instructionBased)
                        bsonData
                    else
                        BSONDocument(instruction -> bsonData)
                }

                // Perform the data write
                val result = collection.update(selector, modifier, upsert = true)

                result onComplete {
                    case Failure(e) => {
                        Logger.error(s"Failed to perform update with error ${e.getMessage}", error = e)
                        callback(false)
                    }
                    case Success(value) => {
                        Logger.trace(s"Update performed successfully: ${value}", Logger.HYPER_VERBOSE_INTENSITY)
                        callback(true)
                    }
                }

                if (wait) {
                    Await.result(result, waitTime)
                    Tools.sleep(resultDelay)
                }

                success = true
            }
            catch {
                case e: TimeoutException => {
                    retryCount += 1
                    Logger.warn(s"Update to collection failed due to timeout - attempting retry ${retryCount}: ${e.getMessage}", error = e)
                }
                case e: Throwable => {
                    retryCount += 1
                    Logger.warn(s"Update to collection failed - attempting retry ${retryCount}: ${e.getMessage}", error = e)
                    Tools.sleep(defaultWaitTime)
                }
            }
        }
    }

    def delete(query: Map[String, Any], callback: Function1[Boolean, Any] = (status) => {}, wait: Boolean = defaultWriteWait, waitTime: FiniteDuration = defaultWaitTime, retries: Int = defaultRetries) = {
        var retryCount = 0
        var success = false

        while (!success && retryCount < retries) {
            try {
                if (!connected)
                    throw new Exception("Connection not yet fully established")

                // Setup filter for setting selector
                val select = Mongo.mongoConvert(query)
                var selectData: BSONDocument = BSONDocument()

                // Set data points
                select.foreach { case (key: String, value: BSONValue) =>
                    selectData = selectData ++ BSONDocument(key -> value)
                }

                // Set query information against which to set data
                val selector = selectData

                // Perform the data write
                val result = collection.remove(selector)

                result onComplete {
                    case Failure(e) => {
                        Logger.error(s"Failed to perform update with error ${e.getMessage}", error = e)
                        callback(false)
                    }
                    case Success(value) => {
                        Logger.trace(s"Update performed successfully: ${value}", Logger.HYPER_VERBOSE_INTENSITY)
                        callback(true)
                    }
                }

                if (wait) {
                    Await.result(result, waitTime)
                    Tools.sleep(resultDelay)
                }

                success = true
            }
            catch {
                case e: TimeoutException => {
                    retryCount += 1
                    Logger.warn(s"Delete from collection failed due to timeout - attempting retry ${retryCount}: ${e.getMessage}", error = e)
                }
                case e: Throwable => {
                    retryCount += 1
                    Logger.warn(s"Delete from collection failed - attempting retry ${retryCount}: ${e.getMessage}", error = e)
                    Tools.sleep(defaultWaitTime)
                }
            }
        }
    }

    def readFirst(query: Map[String, Any], filter: Map[String, Any], callback: Function1[Map[String, Any], Any], sort: Tuple2[String, Int] = ("id", 1), wait: Boolean = defaultReadWait, waitTime: FiniteDuration = defaultWaitTime, retries: Int = defaultRetries) = {
        var retryCount = 0
        var success = false

        while (!success && retryCount < retries) {
            try {
                if (!connected)
                    throw new Exception("Connection not yet fully established")

                // Setup filter for setting selector
                val select = Mongo.mongoConvert(query)
                var selectData: BSONDocument = BSONDocument()

                // Set data points
                select.foreach { case (key: String, value: BSONValue) =>
                    selectData = selectData ++ BSONDocument(key -> value)
                }

                // Create BSON document for writing
                val project = Mongo.mongoConvert(filter)
                var projectData: BSONDocument = BSONDocument()

                // Set data points
                project.foreach { case (key: String, value: BSONValue) =>
                    projectData = projectData ++ BSONDocument(key -> value)
                }

                // Set query information against which to obtain data
                val selector = selectData

                // Set projection to determine which data to return
                val projection = projectData

                // Establish sort order
                val sortOrder = BSONDocument(sort._1 -> sort._2)

                val result: Future[Option[BSONDocument]] = collection.find(selector, projection).sort(sortOrder).one[BSONDocument]

                // Return the results based on the search
                result onComplete {
                    case Failure(e) => {
                        throw e
                        Logger.warn(s"Failed to obtain data from collection: ${e.getMessage}", error = e)
                    }
                    case Success(Some(value)) => {
                        val map = Mongo.bsonDocumentToMap(value)
                        callback(map)
                    }
                    case Success(None) => {
                        callback(Map.empty[String, Any])
                    }
                }

                if (wait) {
                    Await.result(result, waitTime)
                    Tools.sleep(resultDelay)
                }

                success = true
            }
            catch {
                case e: TimeoutException => {
                    retryCount += 1
                    Logger.warn(s"Data query from collection failed due to timeout - attempting retry ${retryCount}: ${e.getMessage}", error = e)
                }
                case e: Throwable => {
                    retryCount += 1
                    Logger.warn(s"Data query from collection failed - attempting retry ${retryCount}: ${e.getMessage}", error = e)
                    Tools.sleep(defaultWaitTime)
                }
            }
        }
    }

    def read(query: Map[String, Any], filter: Map[String, Any], callback: Function1[List[Map[String, Any]], Any], sort: Tuple2[String, Int] = ("id", 1), resultCount: Int = defaultResultCount, wait: Boolean = defaultReadWait, waitTime: FiniteDuration = defaultWaitTime, retries: Int = defaultRetries) = {
        var retryCount = 0
        var success = false

        while (!success && retryCount < retries) {
            try {
                if (!connected)
                    throw new Exception("Connection not yet fully established")

                // Setup filter for setting selector
                val select = Mongo.mongoConvert(query)
                var selectData: BSONDocument = BSONDocument()

                // Set data points
                select.foreach { case (key: String, value: BSONValue) =>
                    selectData = selectData ++ BSONDocument(key -> value)
                }

                // Create BSON document for writing
                val project = Mongo.mongoConvert(filter)
                var projectData: BSONDocument = BSONDocument()

                // Set data points
                project.foreach { case (key: String, value: BSONValue) =>
                    projectData = projectData ++ BSONDocument(key -> value)
                }

                // Set query information against which to obtain data
                val selector = selectData

                // Set projection to determine which data to return
                val projection = projectData

                // Establish sort order
                val sortOrder = BSONDocument(sort._1 -> sort._2)

                val result: Future[List[BSONDocument]] = collection.find(selector, projection).sort(sortOrder).
                    cursor[BSONDocument](ReadPreference.nearest).collect[List](resultCount, Cursor.FailOnError[List[BSONDocument]]())

                // Return the results based on the search
                result onComplete {
                    case Failure(e) => {
                        throw e
                        Logger.error(s"Failed to read results from collection: ${e.getMessage}", error = e)
                    }
                    case Success(list) => {
                        val outcome: ListBuffer[Map[String, Any]] = ListBuffer.empty[Map[String, Any]]

                        list foreach { element =>
                            outcome += Mongo.bsonDocumentToMap(element)
                        }

                        callback(outcome.toList)
                    }
                }

                if (wait) {
                    Await.result(result, waitTime)
                    Tools.sleep(resultDelay)
                }

                success = true
            }
            catch {
                case e: TimeoutException => {
                    retryCount += 1
                    Logger.warn(s"Obtain results from collection failed due to timeout - attempting retry ${retryCount}: ${e.getMessage}", error = e)
                }
                case e: Throwable => {
                    retryCount += 1
                    Logger.warn(s"Obtain results from collection failed - attempting retry ${retryCount}: ${e.getMessage}", error = e)
                    Tools.sleep(defaultWaitTime)
                }
            }
        }
    }

    def random(resultCount: Int = defaultResultCount, callback: Function1[List[Map[String, Any]], Any], wait: Boolean = defaultReadWait, waitTime: FiniteDuration = defaultWaitTime, retries: Int = defaultRetries) = {
        var retryCount = 0
        var success = false

        while (!success && retryCount < retries) {
            try {
                if (!connected)
                    throw new Exception("Connection not yet fully established")

                val result: Future[List[BSONDocument]] = collection.aggregate(BSONAggregationFramework.Sample(resultCount)).map(_.head[BSONDocument])

                // Return the results based on the search
                result onComplete {
                    case Failure(e) => {
                        throw e
                        Logger.error(s"Failed to read results from collection: ${e.getMessage}", error = e)
                    }
                    case Success(list) => {
                        val outcome: ListBuffer[Map[String, Any]] = ListBuffer.empty[Map[String, Any]]

                        list foreach { element =>
                            outcome += Mongo.bsonDocumentToMap(element)
                        }

                        callback(outcome.toList)
                    }
                }

                if (wait) {
                    Await.result(result, waitTime)
                    Tools.sleep(resultDelay)
                }

                success = true
            }
            catch {
                case e: TimeoutException => {
                    retryCount += 1
                    Logger.warn(s"Obtain results from collection failed due to timeout - attempting retry ${retryCount}: ${e.getMessage}", error = e)
                }
                case e: Throwable => {
                    retryCount += 1
                    Logger.warn(s"Obtain results from collection failed - attempting retry ${retryCount}: ${e.getMessage}", error = e)
                    Tools.sleep(defaultWaitTime)
                }
            }
        }
    }


    def count(query: Map[String, Any], callback: (Int) => Any, limit: Int = defaultCountLimit, skip: Int = defaultCountSkip, wait: Boolean = defaultReadWait, waitTime: FiniteDuration = defaultWaitTime, retries: Int = defaultRetries): Unit = {
        var retryCount = 0
        var success = false

        while (!success && retryCount < retries) {
            try {
                if (!connected)
                    throw new Exception("Connection not yet fully established")

                // Setup filter for setting selector
                val select = Mongo.mongoConvert(query)
                var selectData: BSONDocument = BSONDocument()

                // Set data points
                select.foreach { case (key: String, value: BSONValue) =>
                    selectData = selectData ++ BSONDocument(key -> value)
                }

                // Set query information against which to obtain data
                val selector = selectData

                val result: Future[Int] = collection.count(Some(selector), limit, skip)

                // Return the results based on the search
                result onComplete {
                    case Failure(e) => {
                        throw e
                        Logger.error(s"Failed to read results from collection: ${e.getMessage}", error = e)
                    }
                    case Success(count) => {
                        callback(count)
                    }
                }

                if (wait) {
                    Await.result(result, waitTime)
                    Tools.sleep(resultDelay)
                }

                success = true
            }
            catch {
                case e: TimeoutException => {
                    retryCount += 1
                    Logger.warn(s"Obtain count from collection failed due to timeout - attempting retry ${retryCount}: ${e.getMessage}", error = e)
                }
                case e: Throwable => {
                    retryCount += 1
                    Logger.warn(s"Obtain count from collection failed - attempting retry ${retryCount}: ${e.getMessage}", error = e)
                    Tools.sleep(defaultWaitTime)
                }
            }
        }
    }


    def establish = {
        Logger.info(s"Establishing collection ${collection.name}")
        collection.create()
    }


    /**
      * Clear the collection of all entries
      */
    def clear: Unit = {
        Logger.info(s"Clearing all documents from collection ${collection.name}")

        /*
        // Set map to all documents in the collection
        val selector = Map.empty[String, Any]

        // Clear the collection
        delete(selector, (result) => {
            Logger.info(s"Clearing all documents from collection ${collection.name} complete")
        }, wait = true)
        */

        collection.drop(false)
        Logger.info(s"Collection table remove for collection ${collection.name}")
    }


    def ensureIndex(field: String, indexType: Int, unique: Boolean = true) = {
        try {
            val iType = {
                indexType match {
                    case 1 => IndexType.Hashed
                    case 2 => IndexType.Ascending
                    case 3 => IndexType.Text
                }
            }

            collection.indexesManager.ensure(Index(Seq((field, iType)), Some(s"${field}Index"), unique))
        }
        catch {
            case e: Throwable => Logger.warn(s"Unable to set index for collection: ${e.getMessage}", error = e)
        }
    }


    /**
      * Check the collection store of the cache to ensure it is online and available to connect
      *
      * @return
      */
    def checkConnectivity: Boolean = {
        var retryCount = 0
        var success = false

        while (!success && retryCount < defaultRetries) {
            try {
                // Get driver and set options for the collection connection for testing
                val driver = new reactivemongo.api.MongoDriver
                val options = MongoConnectionOptions(authMode = ScramSha1Authentication,
                    nbChannelsPerNode = (Configuration.config \ "collections" \ "channelsPerNode").as[Int],
                    connectTimeoutMS = (Configuration.config \ "collections" \ "connectTimeout").as[Int] * 1000,
                    tcpNoDelay = (Configuration.config \ "collections" \ "tcpNoDelay").as[Boolean])


                // Set the credentials and connection information for testing
                val credentials = List(Authenticate(dbName, username, password))
                val connection = driver.connection(servers, options = options, authentications = credentials)

                // Determine a failover strategy for connection attempts for testing
                val failOverStrategy = FailoverStrategy(
                    initialDelay = (Configuration.config \ "collections" \ "failOverDelay").as[Int] seconds,
                    retries = (Configuration.config \ "collections" \ "failOverRetries").as[Int],
                    delayFactor = attemptNumber => 1 + attemptNumber * 0.5)

                // Connect to the collection dataset for testing
                val database = connection.database(dbName, failOverStrategy)

                // Test to see if database is up
                val isConnected: Future[Boolean] = database.map { list =>
                    list match {
                        case sth: DefaultDB => true
                        case _ => false
                    }
                }.recover {
                    case error: Throwable => {
                        Logger.error(s"Error while attempting to connect to collection: ${error.getMessage}", error = error)
                        false
                    }
                }
                val timeout = defaultConnectWait

                Await.result(isConnected, timeout)
                success = true
            }
            catch {
                case e: TimeoutException => {
                    retryCount += 1
                    Logger.warn(s"Unable to reach collection due to timeout - attempting check connection retry ${retryCount}: ${e.getMessage}", error = e)
                }
                case e: Throwable => {
                    retryCount += 1
                    Logger.warn(s"Unable to reach collection - attempting check connection retry ${retryCount}: ${e.getMessage}", error = e)
                    Tools.sleep(defaultConnectWait)
                }
            }
        }

        success
    }
}

/**
  * The BSONMap object helper provides implicit methods that act as converters to allow for translation of maps to bson documents
  */
object BSONMap {
    /**
      * Read in a bson document and convert to a map
      *
      * @param vr
      * @tparam V
      * @return
      */
    implicit def MapReader[V](implicit vr: BSONDocumentReader[V]): BSONDocumentReader[Map[String, V]] = new BSONDocumentReader[Map[String, V]] {
        def read(bson: BSONDocument): Map[String, V] = {
            val elements = bson.elements.map { tuple =>
                // assume that all values in the document are BSONDocuments
                tuple.name -> vr.read(tuple.value.seeAsTry[BSONDocument].get)
            }
            elements.toMap
        }
    }

    /**
      * Read in a map and convert to a bson document
      *
      * @param vw
      * @tparam V
      * @return
      */
    implicit def MapWriter[V](implicit vw: BSONDocumentWriter[V]): BSONDocumentWriter[Map[String, V]] = new BSONDocumentWriter[Map[String, V]] {
        def write(map: Map[String, V]): BSONDocument = {
            val elements = map.toStream.map { tuple =>
                tuple._1 -> vw.write(tuple._2)
            }
            BSONDocument(elements)
        }
    }
}


object Mongo {
    /**
      * This method performs a data conversion from a particular data type to the mongo equivalent for the purpose of storage
      * in a mongo document/collection storage database
      *
      * @param dataPoints
      * @return
      */
    def mongoConvert(dataPoints: Map[String, Any]): Map[String, BSONValue] = {
        // Setup the output data
        val bsonDataPoints: mutable.HashMap[String, BSONValue] = mutable.HashMap.empty[String, BSONValue]

        // Run through each data point and perform data conversion
        dataPoints foreach {
            case (key: String, value: Any) => {
                // Perform the conversion as possible using the helper method
                val bsonValue: BSONValue = anyToBSONConverter(value)

                // Store the resulting data
                bsonDataPoints += (key -> bsonValue)
            }
        }

        bsonDataPoints.toMap[String, BSONValue]
    }

    /**
      * This method is a helper method for mongoConvert that attempts to determine the data type provided to it and convert
      * to the equivalent mongo data type
      *
      * @param value
      * @return
      */
    def anyToBSONConverter(value: Any): BSONValue = {
        value match {
            // Handle basic data types
            case value: String => BSONString(value)
            case value: Integer => BSONInteger(value)
            case value: Long => BSONLong(value)
            case value: Double => BSONDouble(value)
            case value: Boolean => BSONBoolean(value)
            case value: Array[Byte] => BSONBinary(value, GenericBinarySubtype)
            // Handle a map data type that may be recursive, handling as a document
            case value: Map[_, _] => {
                var map: BSONDocument = BSONDocument()

                value foreach { case (key, element) =>
                    map = map ++ BSONDocument(key.toString -> anyToBSONConverter(element))
                }

                map
            }
            // Handle a hash map data type that may be recursive, handling as a document
            case value: mutable.HashMap[_, _] => {
                var map: BSONDocument = BSONDocument()

                value foreach { case (key, element) =>
                    map = map ++ BSONDocument(key.toString -> anyToBSONConverter(element))
                }

                map
            }
            // Handle an iterable data type converting it to an array
            case value: Iterable[_] => {
                val list: ListBuffer[BSONValue] = ListBuffer.empty

                value foreach { element =>
                    list += anyToBSONConverter(element)
                }

                BSONArray(list)
            }
            case value: Javascript => BSONJavaScript(value.script)
            case value: BSONValue => value
            // Otherwise assuming a blank string as the type is unhandled
            case _ => BSONString("")
        }
    }


    /**
      *
      * @param bsonDoc
      * @return
      */
    def bsonDocumentToMap(bsonDoc: BSONDocument): Map[String, Any] = {
        val results: mutable.HashMap[String, Any] = mutable.HashMap.empty[String, Any]

        /*
        Perform conversion of bsonvalue types, handling a subset of the below
        BSONArray, BSONBinary, BSONBoolean, BSONDBPointer, BSONDateTime, BSONDocument, BSONDouble, BSONInteger,
        BSONJavaScript, BSONJavaScriptWS, BSONLong, BSONMaxKey, BSONMinKey, BSONNull, BSONObjectID, BSONRegex,
        BSONString, BSONSymbol, BSONTimestamp, BSONUndefined
         */
        bsonDoc.elements foreach { element =>
            val key = element.name
            val value: Any = bsonToAnyConverter(element.value)
            results += (key -> value)
        }

        results.toMap
    }

    /**
      *
      * @param value
      * @return
      */
    def bsonToAnyConverter(value: BSONValue): Any = {
        value match {
            case value: BSONString => value.value
            case value: BSONInteger => value.value
            case value: BSONLong => value.value
            case value: BSONDouble => value.value
            case value: BSONBoolean => value.value
            case value: BSONObjectID => value.hashCode
            case value: BSONSymbol => value.value
            case value: BSONTimestamp => value.value
            case value: BSONDateTime => value.value
            case value: BSONBinary => value.value.readArray(value.value.size)
            case value: BSONArray => {
                val list: ListBuffer[Any] = ListBuffer.empty[Any]

                value.values foreach { item =>
                    list += bsonToAnyConverter(item)
                }

                list.toList
            }
            case value: BSONDocument => {
                val hashMap: mutable.HashMap[String, Any] = mutable.HashMap.empty[String, Any]

                value.elements foreach { element =>
                    val key = element.name
                    val item = bsonToAnyConverter(element.value)
                    hashMap += (key -> item)
                }

                hashMap.toMap[String, Any]
            }
            case value: BSONJavaScript => Javascript(value.value)
            case _ => ""
        }
    }

    case class Javascript(script: String)
}

