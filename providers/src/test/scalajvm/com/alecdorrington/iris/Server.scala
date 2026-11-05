package com.alecdorrington.iris

import cats.effect.{IO, Resource}
import java.io.{BufferedInputStream, InputStream, IOException}
import java.net.{InetAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Promise

/**
  * A local server over the JDK's sockets, which sees a client close its
  * connection as soon as it does, though nothing is being written.
  */
object Server:

  /**
    * Serves one request, answering it with `reply` in one chunk and then
    * nothing more, or not at all.
    */
  def apply(reply: Option[String]): Resource[IO, Served] = Resource
    .make(IO(Serving(reply)))(serving => IO(serving.stop()))
    .map(_.served)

  private final class Serving(reply: Option[String]):

    private val socket     = ServerSocket(0, 1, InetAddress.getLoopbackAddress)
    private val connection = AtomicReference(Option.empty[Socket])
    private val received   = Promise[Unit]()
    private val closed     = Promise[Unit]()

    val served: Served = Served(
      s"http://127.0.0.1:${ socket.getLocalPort }",
      received.future,
      closed.future,
    )

    private val thread = Thread(() => serve())
    thread.setDaemon(true)
    thread.start()

    def stop(): Unit =
      socket.close()
      connection.get.foreach(_.close())

    /** Answers the request, then reads until the client closes. */
    private def serve(): Unit =
      try
        val accepted = socket.accept()
        connection.set(Some(accepted))
        val in = BufferedInputStream(accepted.getInputStream)
        in.readNBytes(length(in))
        received.success(())
        reply.foreach(answer(accepted, _))
        while in.read() != -1 do ()
      catch case _: IOException => ()
      finally closed.trySuccess(())

    private def answer(accepted: Socket, text: String): Unit =
      val out  = accepted.getOutputStream
      val body = text.getBytes(UTF_8)
      out.write(
        ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
          "Transfer-Encoding: chunked\r\n\r\n" +
          s"${ body.length.toHexString }\r\n").getBytes(UTF_8),
      )
      out.write(body)
      out.write("\r\n".getBytes(UTF_8))
      out.flush()

    /** Reads a request's head, giving the length of its body. */
    private def length(in: InputStream): Int = Iterator
      .continually(line(in))
      .takeWhile(_.nonEmpty)
      .toList
      .collectFirst:
        case header if header.toLowerCase.startsWith("content-length:") =>
          header.drop("content-length:".length).trim.toInt
      .getOrElse(0)

    private def line(in: InputStream): String = Iterator
      .continually(in.read())
      .takeWhile(byte => byte != '\n' && byte != -1)
      .map(_.toChar)
      .mkString
      .stripSuffix("\r")
