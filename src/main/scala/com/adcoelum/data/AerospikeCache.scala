package com.adcoelum.data

import com.adcoelum.common.{Configuration, Logger, Tools}
import com.aerospike.client._
import com.aerospike.client.policy.{QueryPolicy, ScanPolicy, WritePolicy}
import com.aerospike.client.query.{Filter, IndexType, RecordSet, Statement}

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer
import scala.reflect.runtime.universe._
import scala.collection.JavaConverters._
import scala.collection.mutable


// Defines the call for a connection to a cache
case class CacheConnectionData(client: AerospikeClient, writePolicy: WritePolicy, scanPolicy: ScanPolicy, namespace: String, set: String)


class AerospikeCache extends CacheCore {
    // Set connection variables for cache
    val cacheServer = Configuration.cacheStore("server").asInstanceOf[String]
    val cachePort = Configuration.cacheStore("port").asInstanceOf[Integer]

    // Establish core variables for cache connection and set defaults if available
    var cache: AerospikeClient = _
    var writePolicy: WritePolicy = _
    var scanPolicy: ScanPolicy = _
    var queryPolicy: QueryPolicy = _
    var namespace: String = _
    var set: String = _
    val connection: CacheConnection[AerospikeClient] = new CacheConnection[AerospikeClient]

    def connect(cacheName: String, cacheSet: String) = {
        var retryCount = 0
        var success = false

        while (!success && retryCount < defaultRetries) {
            try {
                // Establish a connection to the cache
                cache = new AerospikeClient(cacheServer, cachePort)

                // Initialize the cache write and read policies
                writePolicy = new WritePolicy()
                writePolicy.sendKey = true

                scanPolicy = new ScanPolicy()
                scanPolicy.sendKey = true

                queryPolicy = new QueryPolicy()
                queryPolicy.sendKey = true

                namespace = cacheName
                set = cacheSet

                connection.set(cache)

                Tools.sleep(defaultConnectWait)
                success = true
            }
            catch {
                case e: Exception => {
                    retryCount += 1
                    Logger.warn(s"Connection attempt failed - attempting retry ${retryCount}: ${e.getMessage}", error = e)
                }
            }
        }
    }

    def setConnection(connectionMap: Map[String, Any]) = {
        var retryCount = 0
        var success = false

        while (!success && retryCount < defaultRetries) {
            try {
                // Establish a connection to the cache
                if (connectionMap.contains("client"))
                    cache = connectionMap("client").asInstanceOf[AerospikeClient]
                if (connectionMap.contains("writePolicy"))
                    writePolicy = connectionMap("writePolicy").asInstanceOf[WritePolicy]
                if (connectionMap.contains("scanPolicy"))
                    scanPolicy = connectionMap("scanPolicy").asInstanceOf[ScanPolicy]
                if (connectionMap.contains("queryPolicy"))
                    queryPolicy = connectionMap("queryPolicy").asInstanceOf[QueryPolicy]
                if (connectionMap.contains("namespace"))
                    namespace = connectionMap("namespace").asInstanceOf[String]
                if (connectionMap.contains("set"))
                    set = connectionMap("set").asInstanceOf[String]

                connection.set(cache)

                Tools.sleep(defaultConnectWait)
                success = true
            }
            catch {
                case e: Exception => {
                    retryCount += 1
                    Logger.warn(s"Connection attempt failed - attempting retry ${retryCount}: ${e.getMessage}", error = e)
                }
            }
        }
    }

    def getConnection[X]: CacheConnection[X] = {
        connection.asInstanceOf[CacheConnection[X]]
    }

    def setCache(cacheName: String, cacheSet: String) = {
        namespace = cacheName
        set = cacheSet
    }

    def get[X:TypeTag](key: String, id: String): X = {
        val keyId: Key = new Key(namespace, set, key)
        val result: Record = cache.get(writePolicy, keyId)

        if (result == null)
            return null.asInstanceOf[X]

        typeOf[X] match {
            case s if s =:= typeOf[String] => result.getString(id).asInstanceOf[X]
            case l if l =:= typeOf[Long] => result.getLong(id).asInstanceOf[X]
            case b if b =:= typeOf[Boolean] => result.getBoolean(id).asInstanceOf[X]
            case by if by =:= typeOf[Byte] => result.getByte(id).asInstanceOf[X]
            case i if i =:= typeOf[Int] => result.getInt(id).asInstanceOf[X]
            case d if d =:= typeOf[Double] => result.getDouble(id).asInstanceOf[X]
            case f if f =:= typeOf[Float] => result.getFloat(id).asInstanceOf[X]
            case s if s =:= typeOf[Short] => result.getShort(id).asInstanceOf[X]
            case l if l =:= typeOf[List[_]] => result.getList(id).asInstanceOf[X]
            case m if m =:= typeOf[Map[_,_]] => result.getMap(id).asInstanceOf[X]
        }
    }

    def get(key: String): Map[String, Any] = {
        val results = mutable.HashMap.empty[String, Any]

        val keyId: Key = new Key(namespace, set, key)
        val result: Record = cache.get(writePolicy, keyId)

        if (result == null)
            return Map.empty[String, Any]

        result.bins.asScala foreach { item =>
            val identifier = item._1
            val value = item._2
            results += (identifier -> value)
        }

        results.toMap
    }

    def put(key: String, id: String, value: Any): Boolean = {
        val keyId: Key = new Key(namespace, set, key)
        val data: Bin = new Bin(id, value)

        cache.put(writePolicy, keyId, data)
        true
    }

    def put(key: String, values: Map[String, Any]): Boolean = {
        val data: ListBuffer[Bin] = ListBuffer.empty
        val keyId: Key = new Key(namespace, set, key)

        values foreach { idValue =>
            data += new Bin(idValue._1, idValue._2)
        }

        cache.put(writePolicy, keyId, data:_*)
        true
    }

    def find(field: String, value: String, start: Int, count: Int): List[Map[String, Any]] = {
        val statement = new Statement
        statement.setNamespace(namespace)
        statement.setSetName(set)

        statement.setFilters(Filter.equal(field, value))

        val records = cache.query(queryPolicy, statement)

        val results: ListBuffer[Map[String, Any]] = ListBuffer.empty
        try {
            var counter = 0
            while (records.next() && counter < start + count) {
                if (counter >= start)
                {
                    val key = records.getKey
                    val record = records.getRecord

                    val result: mutable.HashMap[String, Any] = mutable.HashMap.empty
                    result += ("key" -> key.userKey.toString)

                    record.bins foreach { bin =>
                        result += (bin._1 -> bin._2)
                    }

                    results += result.toMap
                }

                counter += 1
            }
        }
        finally {
            records.close
        }

        results.toList
    }

    def findPointer(field: String, value: String): Any = {
        val statement = new Statement
        statement.setNamespace(namespace)
        statement.setSetName(set)

        statement.setFilters(Filter.equal(field, value))

        val records: RecordSet = cache.query(queryPolicy, statement)

        records
    }

    def delete(key: String): Boolean = {
        val keyId: Key = new Key(namespace, set, key)

        cache.delete(writePolicy, keyId)
    }

    def establish = {
        // Not applicable
        Logger.info(s"Establishing cache set ${namespace}:${set}")
    }

    def clear = {
        Logger.info(s"Clearing all records from cache ${namespace}:${set}")

        // Perform clearance of each entry
        var count = 0
        try {
            cache.scanAll(scanPolicy, namespace, set, new ScanCallback() {
                @throws(classOf[AerospikeException])
                def scanCallback(key: Key, record: Record) = {
                    cache.delete(writePolicy, key)
                    count += 1
                    if (count % 500 == 0)
                        Logger.trace(s"Removing records from cache - current count ${count}")
                }
            })

            Logger.debug("Deleted " + count + " records from cache set " + set)
        }
        catch {
            case e: AerospikeException => Logger.trace(s"Delete existing cache data error ${e.getMessage}")
            case e: Throwable => Logger.trace(s"Error while attempting to clear cache: ${e.getMessage}")
        }
    }

    def ensureIndex(field: String, indexType: Int, unique: Boolean = true) = {
        try {
            val iType = {
                indexType match {
                    case 1 => IndexType.STRING
                    case 2 => IndexType.NUMERIC
                }
            }

            cache.createIndex(null, namespace, set, field, field, iType)
        }
        catch {
            case e: Throwable => Logger.warn(s"Unable to set index for cache: ${e.getMessage}", error = e)
        }
    }

    /**
      * Check the connectivity of the cache to ensure it is online and available to connect
      *
      * @return
      */
    def checkConnectivity: Boolean = {
        var retryCount = 0
        var success = false

        while (!success && retryCount < defaultRetries) {
            try {
                // Establish a connection to the cache for testing
                new AerospikeClient(cacheServer, cachePort)

                // Initialize write and read policies for testing
                val writePolicy = new WritePolicy()
                writePolicy.sendKey = true

                val scanPolicy = new ScanPolicy()
                scanPolicy.sendKey = true

                success = true
            }
            catch {
                case e: Exception => {
                    retryCount += 1
                    Logger.warn(s"Unable to reach cache - attempting check connection retry ${retryCount}: ${e.getMessage}", error = e)
                }
            }
        }

        success
    }
}
