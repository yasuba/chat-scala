package maya.chatter

import cats.effect._
import cats.effect.concurrent.Ref
import fs2.Stream
import fs2.concurrent.{Queue, Topic}
import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`
import org.http4s.server.websocket._
import org.http4s.websocket.WebSocketFrame

import scala.concurrent.ExecutionContext

class ChatterRoutes[F[_]](ec: ExecutionContext,
                          contextShift: ContextShift[F],
                          queue: Queue[F, FromClient],
                          topic: Topic[F, ToClient],
                          chatState: Ref[F, State]
                         )(implicit F: ConcurrentEffect[F], timer: Timer[F]) extends Http4sDsl[F] {

  implicit val cs: ContextShift[F] = contextShift

  def routes: PartialFunction[Request[F], F[Response[F]]] = {

    case GET -> Root / "ws" / userName =>
      val toClient = topic
        .subscribe(1000)
        .map { toClientMessage => WebSocketFrame.Text(toClientMessage.message)}

      WebSocketBuilder[F].build(toClient, wsfStream =>
        wsfStream.collect {
          case WebSocketFrame.Text(text, _) =>
            FromClient(userName, text)
        }.through(queue.enqueue)
      )

    case GET -> Root / "account" / userName =>
      val outputStream: Stream[F, String] = Stream.eval(chatState.get)
        .map { state =>
          val user = state.users.find(u => u.userName == userName).map { user =>
            user.userName
          }
            .getOrElse("User not found")

          val usersMessages = state.messages.filter(msg => msg.userName == user).map(_.message)

          s"""
            |<html>
            |<title>${user}'s messages</title>
            |<body>
            |<ul>${usersMessages}</ul>
            |</body>
            |</html>
          """.stripMargin

        }

      Ok(outputStream, `Content-Type`(MediaType.text.html))


    case GET -> Root / "metrics" =>
      val outputStream: Stream[F, String] = Stream
        .eval(chatState.get)
        .map(state =>
          s"""
             |<html>
             |<title>Chat Server State</title>
             |<body>
             |<div>MessageCount: ${state.messageCount}</div>
             |<div>Users: ${state.users.map(_.userName)}</div>
             |</body>
             |</html>
              """.stripMargin)

      Ok(outputStream, `Content-Type`(MediaType.text.html))


  }
}
