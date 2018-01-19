package lab.zlren.streaming.aiop.log

import lab.zlren.streaming.aiop.log.dao.PlatformLogDAO
import lab.zlren.streaming.aiop.log.entity.PlatformLog
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.SparkConf
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.spark.streaming.kafka010.KafkaUtils
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable.ListBuffer

object PlatformLogApp {

	val logger: Logger = LoggerFactory.getLogger(PlatformLogApp.getClass)

	def main(args: Array[String]): Unit = {

		val sparkConf = new SparkConf().setAppName("PlatFormLogApp").setMaster("local[*]")
		val ssc = new StreamingContext(sparkConf, Seconds(6))

		val kafkaParams = Map[String, Object](
			"bootstrap.servers" -> "10.109.246.68:9092",
			"key.deserializer" -> classOf[StringDeserializer],
			"value.deserializer" -> classOf[StringDeserializer],
			"group.id" -> "group",
			"auto.offset.reset" -> "latest",
			"enable.auto.commit" -> (false: java.lang.Boolean)
		)

		val topics = Array("topic_log_platform")

		val stream = KafkaUtils.createDirectStream[String, String](
			ssc,
			PreferConsistent,
			Subscribe[String, String](topics, kafkaParams)
		)

		// _.key()是null，_.value()对我们是有用的，logs是这一批数据
		val logs = stream.map(_.value())

		try {
			val filteredLog = logs.filter(log => {
				if (log == null || log.length <= 0) {
					false
				} else if (log.contains("Environment") || log.contains("ClientCnxn") || log.contains("ZooKeeper") || log.contains("LogAspect")) {
					false
				} else if (!(log.startsWith("INFO") || log.startsWith("ERROR"))) {
					false
				} else {
					true
				}
			})

			filteredLog.map(log => {

				logger.info("当前日志：" + log)

				val values = log.split("  | ")
				val level = values(0)
				val time = values(1) + " " + values(2)
				val file = values(3).substring(1, values(3).length - 1)
				val appId = values(6).split("=")(1)

				if (values(5).contains("NLP_REST")) {

					val domainNlpAction = if (values(7).startsWith("action")) {
						values(7).split("=")(1)
					} else {
						values(7)
					}

					assert(values(8).startsWith("ability"))
					val domainNlpAbility = values(8).substring(0, values(8).length - 1).split("=")(1)

					val domainNlpResult = values(9).split("=")(1)

					val domainNlpResultReason = if (domainNlpResult.equals("FAILED")) {
						values(10).split("=")(1)
					} else {
						""
					}


					PlatformLog(
						level,
						time,
						file,
						appId,
						domainNlpAbility,
						domainNlpAction,
						domainNlpResult,
						domainNlpResultReason,
						"",
						"",
						"",
						"")


				} else if (values(5).contains("AUTH")) {
					PlatformLog("", "", "", "", "", "", "", "", "", "", "", "")

				} else {
					PlatformLog("", "", "", "", "", "", "", "", "", "", "", "")
				}

			}).foreachRDD(rdd => {
				rdd.foreachPartition(partition => {
					val list = new ListBuffer[PlatformLog]
					partition.filter(pair => pair.level.length > 0).foreach(pair => {
						logger.info("被append的数据：" + pair)
						list.append(pair)
					})
					PlatformLogDAO.save(list)
				})
			})
		} catch {
			case e: Exception => {
				logger.error(e.getMessage)
			}
		}

		ssc.start()
		ssc.awaitTermination()
	}
}