package Yosemite.framework.slave

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

import Yosemite.framework._
import Yosemite.framework.master.Master
import Yosemite.framework.slave.ui.SlaveWebUI
import Yosemite.util._
import Yosemite.{Logging, Utils, YosemiteException}
import akka.actor.{Actor, ActorRef, ActorSystem, Address, Props, Terminated}
import akka.remote.{DisassociatedEvent, RemotingLifecycleEvent}
import org.hyperic.sigar.{Sigar, SigarException}

import scala.collection.mutable.HashMap
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

private[Yosemite] class SlaveActor(
                                 ip: String,
                                 port: Int,
                                 webUiPort: Int,
                                 commPort: Int,
                                 masterUrl: String,
                                 workDirPath: String = null,
                                 isDNS: Boolean = false)
  extends Actor with Logging {

  val HEARTBEAT_SEC = System.getProperty("Yosemite.framework.heartbeat", "1").toInt

  val serverThreadName = "ServerThread for Slave@" + Utils.localHostName()
  // TODO: Keep track of local data
  val idsToFlow = new HashMap[(String, String), FlowDescription]
  val DATE_FORMAT = new SimpleDateFormat("yyyyMMddHHmmss") // For slave IDs
  val slaveId = generateSlaveId()
  val publicAddress = {
    val envVar = System.getenv("YOSEMITE_PUBLIC_DNS")
    if (envVar != null) envVar else ip
  }
  var dataServer: DataServer = null
  var master: ActorRef = null
  var masterAddress: Address = null
  var masterWebUiUrl: String = ""
  var YosemiteHome: File = null
  var workDir: File = null
  var webUi: SlaveWebUI = null

  var sigar = new Sigar()
  var lastRxBytes = -1.0
  var lastTxBytes = -1.0

  var curRxBps = 0.0
  var curTxBps = 0.0

  // ExecutionContext for Futures
  implicit var futureExecContext = ExecutionContext.fromExecutor(Utils.newDaemonCachedThreadPool())

  override def preStart() {
    logInfo("Starting Yosemite slave %s:%d".format(ip, port))
    YosemiteHome = new File(Option(System.getenv("YOSEMITE_HOME")).getOrElse("."))
    logInfo("Yosemite home: " + YosemiteHome)
    createWorkDir()
    webUi = new SlaveWebUI(this, workDir, isDNS, Some(webUiPort))
    dataServer = new DataServer(commPort, serverThreadName, isDNS)

    webUi.start()
    dataServer.start()
    connectToMaster()
  }

  def createWorkDir() {
    workDir = Option(workDirPath).map(new File(_)).getOrElse(new File(YosemiteHome, "work"))
    try {
      if (!workDir.exists() && !workDir.mkdirs()) {
        logError("Failed to create work directory " + workDir)
        System.exit(1)
      }
    } catch {
      case e: Exception =>
        logError("Failed to create work directory " + workDir, e)
        System.exit(1)
    }
  }

  def connectToMaster() {
    logInfo("Connecting to master " + masterUrl)
    try {
      master = AkkaUtils.getActorRef(Master.toAkkaUrl(masterUrl), context)
      masterAddress = master.path.address
      master ! RegisterSlave(slaveId, ip, port, webUi.boundPort.get, commPort, publicAddress)
      context.system.eventStream.subscribe(self, classOf[RemotingLifecycleEvent])
    } catch {
      case e: Exception =>
        logError("Failed to connect to master", e)
        System.exit(1)
    }
  }

  override def postStop() {
    webUi.stop()
  }

  override def receive = {
    case RegisteredSlave(url) => {
      masterWebUiUrl = url
      logInfo("Successfully registered with master")

      // Do not send stats by default
      val sendStats = System.getProperty("Yosemite.slave.sendStats", "true").toBoolean
      if (sendStats) {
        // Thread to periodically update last{Rx|Tx}Bytes
        context.system.scheduler.schedule(0.millis, (HEARTBEAT_SEC * 1000).millis) {
          updateNetStats()
          master ! Heartbeat(slaveId, curRxBps, curTxBps)
        }
      }
    }

    case RegisterSlaveFailed(message) => {
      logError("Slave registration failed: " + message)
      System.exit(1)
    }

    case Terminated(actor) if actor == master => {
      masterDisconnected()
    }

    case e: DisassociatedEvent if e.remoteAddress == masterAddress =>
      masterDisconnected()

    case RequestSlaveState => {
      sender ! SlaveState(ip, port, slaveId, masterUrl, curRxBps, curTxBps, masterWebUiUrl)
    }

    case RegisteredCoflow(coflowId) => {
      // TODO: Do something!
      sender ! true
    }

    case UnregisterCoflow(coflowId) => {
      // TODO: Do something!
      sender ! true
    }

    case AddFlow(flowDesc) => {
      // TODO: Do something!
      logInfo("Received AddFlow for " + flowDesc)

      // Update commPort if the end point will be a client
      if (flowDesc.dataType != DataType.INMEMORY) {
        flowDesc.updateCommPort(commPort)
      }

      // Now let the master know and notify the client
      AkkaUtils.tellActor(master, AddFlow(flowDesc))
      sender ! true
    }

    case AddFlows(flowDescs, coflowId, dataType) => {
      // TODO: Do something!
      logInfo("Received AddFlows for coflow " + coflowId)

      // Update commPort if the end point will be a client
      if (dataType != DataType.INMEMORY) {
        flowDescs.foreach(_.updateCommPort(commPort))
      }

      // Now let the master know and notify the client
      AkkaUtils.tellActor(master, AddFlows(flowDescs, coflowId, dataType))
      sender ! true
    }

    case GetFlow(flowId, coflowId, clientId, _, flowDesc) => {
      // TODO: Do something!
      logInfo("Received GetFlow for " + flowDesc)

      sender ! true
    }

    case GetFlows(flowIds, coflowId, clientId, _, flowDescs) => {
      // TODO: Do something!
      logInfo("Received GetFlows for " + flowIds.size + " flows of coflow " + coflowId)

      sender ! true
    }

    case DeleteFlow(flowId, coflowId) => {
      // TODO: Actually remove
      sender ! true
    }
  }

  def masterDisconnected() {
    // TODO: It would be nice to try to reconnect to the master, but just shut down for now.
    // (Note that if reconnecting we would also need to assign IDs differently.)
    logError("Connection to master failed! Shutting down.")
    System.exit(1)
  }

  /**
    * Update last{Rx|Tx}Bytes before each heartbeat
    * Return the pair (rxBps, txBps)
    */
  def updateNetStats() = {
    var curRxBytes = 0.0;
    var curTxBytes = 0.0;

    try {
      val netIfs = sigar.getNetInterfaceList()
      for (i <- 0 until netIfs.length) {
        val net = sigar.getNetInterfaceStat(netIfs(i))

        val r = net.getRxBytes()
        if (r >= 0) {
          curRxBytes += r
        }

        val t = net.getTxBytes()
        if (t >= 0.0) {
          curTxBytes += t
        }
      }
    } catch {
      case se: SigarException => {
        println(se)
      }
    }

    var rxBps = 0.0
    var txBps = 0.0
    if (lastRxBytes >= 0.0 && lastTxBytes >= 0.0) {
      rxBps = (curRxBytes - lastRxBytes) / HEARTBEAT_SEC;
      txBps = (curTxBytes - lastTxBytes) / HEARTBEAT_SEC;
    }

    lastRxBytes = curRxBytes
    lastTxBytes = curTxBytes

    curRxBps = rxBps
    curTxBps = txBps

    // FIXME: Sometimes Sigar stops responding, and printing something here brings it back!!!
    // This bug also causes Slave actors to stop responding, which causes the client failures.
    logInfo(rxBps + " " + txBps)
  }

  def generateSlaveId(): String = {
    "slave-%s-%s-%d".format(DATE_FORMAT.format(new Date), ip, port)
  }
}

private[Yosemite] object Slave {
  private val systemName = "YosemiteSlave"
  private val actorName = "Slave"
  private val YosemiteUrlRegex = "Yosemite://([^:]+):([0-9]+)".r

  def main(argStrings: Array[String]) {
    val args = new SlaveArguments(argStrings)
    val (actorSystem, _) = startSystemAndActor(args.ip, args.port, args.webUiPort, args.commPort,
      args.master, args.workDir, args.isDNS)
    actorSystem.awaitTermination()
  }

  def startSystemAndActor(
                           host: String,
                           port: Int,
                           webUiPort: Int,
                           commPort: Int,
                           masterUrl: String,
                           workDir: String,
                           isDNS: Boolean = false,
                           slaveNumber: Option[Int] = None): (ActorSystem, Int) = {

    // The LocalYosemiteCluster runs multiple local YosemiteSlaveX actor systems
    val systemName = "YosemiteSlave" + slaveNumber.map(_.toString).getOrElse("")
    val (actorSystem, boundPort) = AkkaUtils.createActorSystem(systemName, host, port)
    val actor = actorSystem.actorOf(Props(new SlaveActor(host, boundPort, webUiPort, commPort,
      masterUrl, workDir, isDNS)), name = "Slave")
    (actorSystem, boundPort)
  }

  /**
    * Returns an `akka.tcp://...` URL for the Slave actor given a YosemiteUrl `Yosemite://host:ip`.
    */
  def toAkkaUrl(YosemiteUrl: String): String = {
    YosemiteUrl match {
      case YosemiteUrlRegex(host, port) =>
        "akka.tcp://%s@%s:%s/user/%s".format(systemName, host, port, actorName)
      case _ =>
        throw new YosemiteException("Invalid master URL: " + YosemiteUrl)
    }
  }

}
