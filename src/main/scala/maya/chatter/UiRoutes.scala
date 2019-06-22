package maya.chatter

import cats.effect.{ConcurrentEffect, ContextShift, IO, Timer}
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpRoutes, Request, Response, StaticFile}

import scala.concurrent.ExecutionContext

class UiRoutes[F[_]](ec: ExecutionContext,
                     contextShift: ContextShift[F]
                    )(implicit F: ConcurrentEffect[F], timer: Timer[F]) extends Http4sDsl[F] {

  implicit val cs: ContextShift[F] = contextShift

  def routes: PartialFunction[Request[F], F[Response[F]]] = {
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
  }

}
