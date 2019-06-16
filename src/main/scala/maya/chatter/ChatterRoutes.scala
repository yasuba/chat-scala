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

  def routes: HttpRoutes[F] = HttpRoutes.of[F] {

    case request @ GET -> Root  =>
      StaticFile
        .fromResource[F]("/static/index.html", ec, Some(request))
        .getOrElseF(NotFound("Couldn't find this resource file"))

    case request @ GET -> Root / "chat.js"  =>
      StaticFile
        .fromResource[F]("/static/chat.js", ec, Some(request))
        .getOrElseF(NotFound("Couldn't find this resource file"))

    case request @ GET -> Root / "style.css"  =>
      StaticFile
        .fromResource[F]("/static/style.css", ec, Some(request))
        .getOrElseF(NotFound("Couldn't find this resource file"))

    case request @ GET -> Root / "bird.png"  =>
      StaticFile
        .fromResource[F]("/static/bird.png", ec, Some(request))
        .getOrElseF(NotFound("Couldn't find this resource file"))

    case request @ GET -> Root / "background.gif"  =>
      StaticFile
        .fromResource[F]("/static/background.gif", ec, Some(request))
        .getOrElseF(NotFound("Couldn't find this resource file"))

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

    case GET -> Root / "metrics" =>
      val outputStream: Stream[F, String] = Stream
        .eval(chatState.get)
        .map(state =>
          s"""
             |<html>
             |<title>Chat Server State</title>
             |<body>
             |<div>MessageCount: ${state.messageCount}</div>
             |</body>
             |</html>
              """.stripMargin)

      Ok(outputStream, `Content-Type`(MediaType.text.html))


  }
}
