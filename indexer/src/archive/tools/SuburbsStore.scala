package com.adcoelum.indexer.property.tools

import com.github.tototoshi.csv._

import scala.collection.mutable.HashMap


class SuburbStore {
    val suburbs: HashMap[String, Tuple2[String, String]] = new HashMap[String, Tuple2[String, String]]()
    
    val reader = CSVReader.open(new java.io.File("./data/postcodes.csv"))
    
    reader.foreach(fields => {
        val sub = fields(0)
        val st = fields(1)
        val pc = fields(2)
        
        suburbs += (sub -> (st, pc))
    })
    
    reader.close()
}

object SuburbStore {
    val suburbStore = new SuburbStore
    
    def initiate = Nil
    
    def findSuburb(suburb: String): Option[Tuple2[String, String]] = suburbStore.suburbs.get(suburb)
}