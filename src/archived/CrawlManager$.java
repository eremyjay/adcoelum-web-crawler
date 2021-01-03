object CrawlManager {
    // Start Monitoring
    // Kamon.start()
    // https://hub.docker.com/r/kamon/grafana_graphite/
    // docker run -d -p 80:8080 -p 8125:8125/udp -p 8126:8126 --name kamon-grafana-dashboard kamon/grafana_graphite

    // Clear Aerospike cache
    clearCache

    // Establish path basic information
    val siteCrawlerPath = "/Users/jeremy/Documents/development/ad_coelum/alpha/crawler/target/scala-2.11/"
    // val siteCrawlerPath = "./"
    val siteCrawlerJar = "siteCrawler-assembly-0.1.0-alpha.jar"
    val classPath = "" //"-cp /Users/jeremy/Documents/development/ad_coelum/alpha/lib/corenlp/stanford-corenlp-3.6.0-models.jar"


    // Setup Sites
    val siteLists: ListBuffer[List[String]] = ListBuffer()

    siteLists += List("http://www.raywhite.com")
    /*
    siteLists += List("https://www.mcgrath.com.au")
    siteLists += List("http://littlerealestate.com.au")
    siteLists += List("http://www.randw.com.au")
    siteLists += List("http://www.belleproperty.com")
    siteLists += List("http://www.raineandhorne.com.au")
    siteLists += List("http://www.raywhite.com")
    */

    /*
    siteLists += List("http://www.propertynow.com.au")
    siteLists += List("http://landmarkharcourts.com.au")
    siteLists += List("http://aushomesforsale.com")
    siteLists += List("http://www.raineandhorne.com.au")
    siteLists += List("http://www.chriswilsonrealestateeden.com.au")
    siteLists += List("http://www.century21.com.au")
    siteLists += List("http://www.ljhooker.com.au")
    siteLists += List("https://www.firstnational.com.au")
    siteLists += List("http://portal.prdnationwide.com.au")
    */


    // Setup spark server
    SparkScala.port(10101)
    SparkScala.get("/hello") {(_,_) => "Hello World"}


    // Setup each crawler
    siteLists foreach { siteList =>
        var sites = ""
        var siteCount = 0

        siteList foreach { site =>
            sites += site + " "
            siteCount += 1
        }

        val minMemSize = 256 // siteCount * 256
        val maxMemSize = 512 // siteCount * 768
        val javaMinMem = "-J-Xms" + minMemSize.toString + "M"
        val javaMaxMem = "-J-Xmx" + maxMemSize.toString + "M"
        val javaOptions = "-J-server -J-XX:+UseG1GC -J-XX:+AggressiveOpts"

        val cmd = "scala " + classPath + " " + javaOptions + " " + javaMinMem + " " + javaMaxMem + " " + siteCrawlerPath + siteCrawlerJar

        (cmd + " " + sites) lineStream_! ProcessLogger(line => System.out.println(line))
    }


    // Clear cache method
    def clearCache(): Unit = {
        val cacheServer = "127.0.0.1"
        val cachePort = 3000
        val namespace = "ad_coelum"
        val set = "crawler"

        val cache = new AerospikeClient(cacheServer, cachePort)
        System.out.println(s"Starting to delete cache records from ${namespace} : ${set}")

        // Initialize policy.
        val writePolicy = new WritePolicy()
        val scanPolicy = new ScanPolicy()

        var count = 0
        try {
            cache.scanAll(scanPolicy, namespace, set, new ScanCallback() {
                @throws(classOf[AerospikeException])
                def scanCallback(key: Key, record: Record) = {
                    cache.delete(writePolicy, key)
                    count += 1
                    if (count % 5000 == 0)
                        System.out.println(s"Removing records - current count ${count}")
                }
            })

            System.out.println("Deleted " + count + " records from set " + set)
        }
        catch {
            case e: AerospikeException => System.out.println(s"Delete existing cache data error ${e.getMessage}");
        }
    }

    implicit def ProcessWithCallback(p: Process) {
        def whenTerminatedDo(callback: Int => Unit) = Future {
            val exitValue = p.exitValue
            callback(exitValue)
        }
    }
}
