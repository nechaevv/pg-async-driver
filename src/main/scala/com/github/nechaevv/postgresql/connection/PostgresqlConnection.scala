package com.github.nechaevv.postgresql.connection

import java.net.InetSocketAddress
import java.security.MessageDigest

import akka.NotUsed
import akka.actor.{Actor, ActorRef, FSM, Props, Stash}
import akka.io.Tcp.Connected
import akka.stream.ActorMaterializer
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.Request
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, Tcp}
import akka.util.ByteString
import com.github.nechaevv.postgresql.protocol.backend._
import com.github.nechaevv.postgresql.protocol.frontend._
import com.typesafe.scalalogging.LazyLogging

/**
  * Created by v.a.nechaev on 11.07.2016.
  */
class PostgresqlConnection(address: InetSocketAddress, database: String, user: String, password: String)(implicit mat: ActorMaterializer)
  extends Actor with FSM[ConnectionState, ConnectionProperties] with ActorPublisher[FrontendMessage] with LazyLogging {

  startWith(Connecting,
    Uninitialized(commandSink = Sink.actorRefWithAck(self, ListenerReady, AckPacket, ListenerCompleted))
  )

  when(Initializing) {
    case Event(ListenerReady, Uninitialized(commandSink)) =>
      val source = Source.actorPublisher[FrontendMessage](Props(classOf[PgMessagePublisher], mat))
        .map(_.encode)
      val sink = Flow[ByteString].via(new PgPacketParser).to(commandSink)
      val flow = Flow.fromSinkAndSourceMat(sink, source)(Keep.right)
      val commandPublisher = Tcp().outgoingConnection(remoteAddress = address, halfClose = false)
        .joinMat(flow)(Keep.right).run()
      sender ! AckPacket
      commandPublisher ! StartupMessage(database, user)
      goto(Connecting) using ConnectionContext(commandPublisher)
  }
  when(Connecting) {
    case Event(Connected(remote, local), ConnectionContext(commandPublisher)) =>
      logger.trace(s"Connected to server $remote")
      commandPublisher ! StartupMessage(database, user)
      goto(StartingUp)
  }
  when(StartingUp) {
    case Event(AuthenticationCleartextPassword, ConnectionContext(commandPublisher)) =>
      logger.info("Requested cleartext auth")
      sender ! AckPacket
      commandPublisher ! PasswordMessage(password)
      goto(Authenticating)
    case Event(AuthenticationMD5Password(salt), ConnectionContext(commandPublisher)) =>
      logger.info("Requested md5 auth")
      sender ! AckPacket
      commandPublisher ! PasswordMessage(md5password(user, password, salt))
      goto(Authenticating)
    case Event(AuthenticationOk, _) =>
      logger.info("Authentication succeeded")
      sender ! AckPacket
      goto(Authenticated)
  }
  when(Authenticating) {
    case Event(AuthenticationOk, _) =>
      logger.info("Authentication succeeded")
      sender ! AckPacket
      goto(Authenticated)
  }
  when(Authenticated) {
    case Event(ReadyForQuery(txStatus), _) =>
      logger.info(s"Ready for query (tx status $txStatus)")
      goto(Ready)
  }
  when(Ready) {
    case Event(cmd: QueryCommand, ConnectionContext(commandPublisher)) =>
      logger.trace(s"Executing query ${cmd.sql}")
      val (oids, values) = cmd.parameters.unzip
      commandPublisher ! Parse("", cmd.sql, oids)
      commandPublisher ! Bind("", "", Seq.fill(cmd.parameters.length)(1), values, Seq.fill(cmd.resultColumnCount)(1))
      commandPublisher ! Execute("", 0)
      commandPublisher ! Sync
      goto(Querying) using QueryContext(commandPublisher, sender)
  }
  when(Querying) {
    case Event(ParseComplete, _) =>
      logger.info("Parse completed")
      sender ! AckPacket
      stay()
    case Event(BindComplete, _) =>
      logger.info("Bind completed")
      sender ! AckPacket
      stay()
    case Event(dataRow: DataRow, QueryContext(_, queryListener)) =>
      sender ! AckPacket
      queryListener ! dataRow
      stay()
    case Event(CommandComplete, QueryContext(commandPublisher, queryListener)) =>
      logger.info("Command completed")
      sender ! AckPacket
      queryListener ! CommandComplete
      goto(Ready) using ConnectionContext(commandPublisher)
  }

  whenUnhandled {
    case Event(event, state) =>
      logger.error(s"Unhandled event $event")
      stop(FSM.Failure(new IllegalStateException()))
  }

  onTermination {
    case StopEvent(_, ConnectionContext(commandPublisher), reason) =>
      logger.info("Connection terminated")
      commandPublisher ! Terminate
  }


  private def md5password(user: String, password: String, salt: Array[Byte]): String = {
    val md = MessageDigest.getInstance("MD5")
    md.update(password.getBytes())
    md.update(user.getBytes())
    md.update(toHex(md.digest()).getBytes())
    md.update(salt)
    "md5" + toHex(md.digest())
  }
  private def toHex(bytes: Array[Byte]): String = bytes.map(b => "%02x".format(b & 0xFF)).mkString

}

class PgMessagePublisher(implicit mat: ActorMaterializer) extends Actor with ActorPublisher[FrontendMessage] with Stash {
  override def receive: Receive = {
    case msg: FrontendMessage => if(totalDemand > 0) {
      onNext(msg)
      sender ! AckPacket
    } else stash()
    case _: Request => unstashAll()
    case source: Source[FrontendMessage, _] =>
      val sink = Sink.actorRefWithAck(self, ListenerReady, AckPacket, ListenerCompleted)
      source.runWith(sink)
  }
}

case object ListenerReady
case object AckPacket
case object ListenerCompleted

sealed trait ConnectionState

case object Initializing extends ConnectionState
case object Connecting extends ConnectionState
case object StartingUp extends ConnectionState
case object Authenticating extends ConnectionState
case object Authenticated extends ConnectionState
case object Ready extends ConnectionState
case object Querying extends ConnectionState

sealed trait ConnectionProperties

case class Uninitialized(commandSink: Sink[Packet, NotUsed]) extends ConnectionProperties
case class ConnectionContext(commandPublisher: ActorRef) extends ConnectionProperties
case class QueryContext(commandPublisher: ActorRef, queryListener: ActorRef) extends ConnectionProperties

case class QueryCommand(sql: String, parameters: Seq[(Int, Option[ByteString])], resultColumnCount: Int)