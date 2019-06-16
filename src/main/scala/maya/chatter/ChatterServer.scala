package maya.chatter

import java.util.concurrent.Executors

import cats.effect._
import cats.effect.concurrent.Ref
import cats.implicits._
import fs2._
import fs2.concurrent.{Queue, Topic}
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}
import scala.util.Properties

object Chatter extends IOApp {
  private val log = LoggerFactory.getLogger(getClass)

  override def run(args: List[String]): IO[ExitCode] = {
    val port: Int = Properties.envOrElse("port", "8080").toInt

    log.info(s"Port is $port")

    for {
      q   <- Queue.unbounded[IO, FromClient]
      t   <- Topic[IO, ToClient](ToClient(""))
      ref <- Ref.of[IO, State](State(0))

      exitCode   <- {
        val messageStream = q
          .dequeue
          .evalMap{fromClient =>
            ref.modify(currentState => (
              State(currentState.messageCount + 1),
              ToClient( s"(${currentState.messageCount}): ${fromClient.userName}: ${fromClient.message}")
            ))
          }
          .through(t.publish)

        val serverStream = ChatterApp[IO](contextShift, q, t, ref).stream(port)
        val combinedStream = Stream(messageStream, serverStream).parJoinUnbounded
          combinedStream.compile.drain.as(ExitCode.Success)
      }
    } yield exitCode
  }
}

class ChatterApp[F[_]](contextShift: ContextShift[F],
                       queue: Queue[F, FromClient],
                       topic: Topic[F, ToClient],
                       ref: Ref[F, State]
                      )(implicit F: ConcurrentEffect[F], timer: Timer[F]) extends Http4sDsl[F] {

  val ec: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(4))

  def stream(port: Int): Stream[F, ExitCode] =
    BlazeServerBuilder[F]
      .bindHttp(port, "0.0.0.0")
      .withHttpApp(new ChatterRoutes[F](ec, contextShift, queue, topic, ref).routes.orNotFound)
      .serve
}

object ChatterApp {
  def apply[F[_]: ConcurrentEffect: Timer](
    contextShift: ContextShift[F],
    queue: Queue[F, FromClient],
    topic: Topic[F, ToClient],
    ref: Ref[F, State]) = new ChatterApp[F](contextShift, queue, topic, ref)
}

case class State(messageCount: Int)
case class FromClient(userName: String, message: String)
case class ToClient(message: String)
