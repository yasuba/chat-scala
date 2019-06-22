package maya.chatter

import java.util.concurrent.Executors

import cats.effect._
import cats.effect.concurrent.Ref
import cats.implicits._
import fs2._
import fs2.concurrent.{Queue, Topic}
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder

import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}
import scala.util.Properties

object Chatter extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val port: Int = Properties.envOrElse("PORT", "8080").toInt

    for {
      q   <- Queue.unbounded[IO, FromClient]
      t   <- Topic[IO, ToClient](ToClient(""))
      ref <- Ref.of[IO, State](State(0, List.empty, List.empty))

      exitCode   <- {
        val messageStream = q
          .dequeue
          .evalMap{fromClient =>
            ref.modify(currentState => (
              State(
                currentState.messageCount + 1,
                User(fromClient.userName) :: currentState.users,
                fromClient :: currentState.messages
              ),
              ToClient(s"${fromClient.userName}: ${fromClient.message}")
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
      .withHttpApp{
        HttpRoutes.of[F] {
          new ChatterRoutes[F](ec, contextShift, queue, topic, ref).routes orElse
            new UiRoutes[F](ec, contextShift).routes
        }.orNotFound
      }
      .serve
}

object ChatterApp {
  def apply[F[_]: ConcurrentEffect: Timer](
    contextShift: ContextShift[F],
    queue: Queue[F, FromClient],
    topic: Topic[F, ToClient],
    ref: Ref[F, State]): ChatterApp[F] =
    new ChatterApp[F](contextShift, queue, topic, ref)
}

case class User(userName: String)
case class State(messageCount: Int, users: List[User], messages: List[FromClient])
case class FromClient(userName: String, message: String)
case class ToClient(message: String)
