package com.alecdorrington.iris
package api

import cats.effect.{Async, Resource}
import cats.effect.std.Dispatcher
import java.net.{Authenticator, CookieHandler, ProxySelector}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.ByteBuffer
import java.time.Duration
import java.util.{List as JavaList, Optional}
import java.util.concurrent.{
  CancellationException, CompletableFuture, CompletionStage, Executor, Flow,
}
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.{SSLContext, SSLParameters}
import sttp.client4.httpclient.fs2.HttpClientFs2Backend

/** What differs between the platforms: on the JVM, the JDK's HTTP client. */
private[iris] object Platform:

  /**
    * Backends of a client's own over one JDK client, made as sttp makes its
    * own, and shut down with them: one sending requests whole, and for each
    * streamed reply, one that can abort it, cancelling its body, which closes
    * its connection.
    */
  def backends[F[_] : Async]: Resource[F, Backends[F]] =
    for
      dispatcher <- Dispatcher.parallel[F]
      executor   <- Resource.eval(Async[F].executor)
      client     <- Resource.make(Async[F].delay(
        HttpClient
          .newBuilder()
          .followRedirects(HttpClient.Redirect.NEVER)
          .connectTimeout(Duration.ofSeconds(30))
          .executor(executor)
          .build(),
      ))(client => Async[F].delay(shutDown(client)))
    yield Backends(
      HttpClientFs2Backend.usingClient[F](client, dispatcher),
      Async[F].delay(exchange(client, dispatcher)),
    )

  /** Whether requests may come from a browser's page, which sends its origin. */
  val browser: Boolean = false

  /**
    * Shuts the client down at once, interrupting its requests still in flight,
    * which closes their connections, as sttp's own backends never do. Closing
    * it would wait for them, and a request given up on may never end. The JDK's
    * clients shut down only from Java 21.
    */
  private def shutDown(client: HttpClient): Unit =
    if Runtime.version.feature >= 21 then client.shutdownNow()

  /** A backend for one streamed reply, over the client, which can abort it. */
  private def exchange[F[_] : Async]
    (
      client: HttpClient,
      dispatcher: Dispatcher[F],
    )
    : Exchange[F] =
    val abortable = Abortable(client)
    Exchange(
      HttpClientFs2Backend.usingClient[F](abortable, dispatcher),
      Async[F].delay(abortable.abort()),
    )

  /**
    * The client, sending as it does, but that [[abort]] ends the bodies of its
    * replies at once. sttp reads a body through fs2's reactive-streams
    * subscriber, which waits for each part uninterruptibly, so a stream dropped
    * while its reply is silent would wait for the next part to cancel it, and
    * for good where none comes.
    */
  private final class Abortable(client: HttpClient) extends HttpClient:

    /** The bodies of the replies so far, or `None` once aborted. */
    private val bodies = AtomicReference(Option(List.empty[AbortableBody[?]]))

    /** Aborts the bodies of every reply, and of any to come. */
    def abort(): Unit = bodies.getAndSet(None).toList.flatten.foreach(_.abort())

    private def watched[T]
      (handler: HttpResponse.BodyHandler[T])
      : HttpResponse.BodyHandler[T] = info =>
      val body = AbortableBody(handler(info))
      if bodies.getAndUpdate(_.map(body :: _)).isEmpty then body.abort()
      body

    override def send[T]
      (
        request: HttpRequest,
        handler: HttpResponse.BodyHandler[T],
      )
      : HttpResponse[T] = client.send(request, watched(handler))

    override def sendAsync[T]
      (
        request: HttpRequest,
        handler: HttpResponse.BodyHandler[T],
      )
      : CompletableFuture[HttpResponse[T]] =
      client.sendAsync(request, watched(handler))

    override def sendAsync[T]
      (
        request: HttpRequest,
        handler: HttpResponse.BodyHandler[T],
        promises: HttpResponse.PushPromiseHandler[T],
      )
      : CompletableFuture[HttpResponse[T]] =
      client.sendAsync(request, watched(handler), promises)

    override def cookieHandler(): Optional[CookieHandler] =
      client.cookieHandler()

    override def connectTimeout(): Optional[Duration] = client.connectTimeout()

    override def followRedirects(): HttpClient.Redirect =
      client.followRedirects()

    override def proxy(): Optional[ProxySelector] = client.proxy()

    override def sslContext(): SSLContext = client.sslContext()

    override def sslParameters(): SSLParameters = client.sslParameters()

    override def authenticator(): Optional[Authenticator] =
      client.authenticator()

    override def version(): HttpClient.Version = client.version()

    override def executor(): Optional[Executor] = client.executor()

  /**
    * A reply's body, which [[abort]] cancels, ending it with an error unless it
    * has ended, so that a read waiting on it is freed. Every signal passes
    * under its lock, so that none overlaps another or follows the body's end;
    * the subscription is cancelled outside it, as the client may hold locks of
    * its own while it signals.
    */
  private final class AbortableBody[T]
    (body: HttpResponse.BodySubscriber[T])
    extends HttpResponse.BodySubscriber[T]:

    // Guarded by this.
    private var subscription = Option.empty[Flow.Subscription]
    private var aborted      = false
    private var ended        = false

    /** Cancels the body and ends it with an error, unless it has ended. */
    def abort(): Unit =
      val cancelled = synchronized:
        aborted = true
        end()
      cancelled.foreach(_.cancel())

    override def getBody(): CompletionStage[T] = body.getBody()

    override def onSubscribe(begun: Flow.Subscription): Unit =
      val cancelled = synchronized:
        subscription = Some(begun)
        body.onSubscribe(begun)
        end()
      cancelled.foreach(_.cancel())

    override def onNext(item: JavaList[ByteBuffer]): Unit = synchronized:
      if !ended then body.onNext(item)

    override def onError(failure: Throwable): Unit = synchronized:
      if !ended then
        ended = true
        body.onError(failure)

    override def onComplete(): Unit = synchronized:
      if !ended then
        ended = true
        body.onComplete()

    /**
      * Ends the body with an error where it was aborted once begun, giving the
      * subscription to cancel. Called under the lock.
      */
    private def end(): Option[Flow.Subscription] =
      if aborted && !ended && subscription.nonEmpty then
        ended = true
        body.onError(CancellationException("The reply was aborted."))
        subscription
      else None
