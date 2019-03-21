package useZio

import fixed.Fixed.{Config, CurrencyApi}
import scalaz.zio.{TaskR, UIO, ZIO}

trait Program extends Algebra {

  import Console._
  import CurrencyApiZ._

  val program = for {
    currencies <- allCurrencies
    currencyIdMap <- ZIO.succeed(currencies.zipWithIndex.map(_.swap).map(pair => (pair._1 + 1, pair._2)).toMap)
    _ <- transaction(currencyIdMap).forever
  } yield ()

  private def transaction(currencyIdMap: Map[Int, String]) = for {
    currencyIdsFormatted <- ZIO.succeed(currencyIdMap.map(pair => s"[${pair._1} - ${pair._2}]").mkString(","))
    _ <- tell(s"Choose a currency you want to convert from - $currencyIdsFormatted")
    fromCurrency <- ask.map(v => currencyIdMap(v.toInt))
    _ <- tell(s"You chose $fromCurrency")
    _ <- tell(s"How much you want to convert?")
    amount <- ask.map(BigDecimal(_))
    _ <- tell(s"Choose a currency want to convert to - $currencyIdsFormatted")
    toCurrency <- ask.map(value => currencyIdMap(value.toInt))
    _ <- tell(s"You chose $toCurrency")
    rate <- exchangeRate(fromCurrency, toCurrency)
    _ <- tell(s"Converted $fromCurrency $amount to $toCurrency at rate $rate")
  } yield ()
}

trait Algebra {

  trait Console {
    def console: Console.Service
  }

  trait ConfigProvider {
    def service: ConfigProvider.Service
  }

  object ConfigProvider {

    trait Service {
      def config: Config
    }

    trait Live extends Service {
      val config = Config()
    }
    object Live extends Live
  }

  object Console {

    trait Service {
      val ask: UIO[String]
      def tell(value: String): UIO[Unit]
    }

    trait Live extends Service {
      override val ask: UIO[String] = UIO.effectTotal(scala.io.StdIn.readLine())
      override def tell(value: String): UIO[Unit] = UIO.effectTotal(println(value))
    }

    object Live extends Live

    val ask: ZIO[Console, Nothing, String] = ZIO.accessM(_.console.ask)
    def tell(value: String): ZIO[Console, Nothing, Unit] = ZIO.accessM(_.console.tell(value))

  }

  trait Logging {
    def logging: Logging.Service
  }

  object Logging {

    trait Service {
      def logInfo(msg: String): UIO[Unit]
      def logWarn(msg: String): UIO[Unit]
      def logError(msg: String): UIO[Unit]
    }

    trait Live extends Service with Console.Live {
      def logInfo(msg: String): UIO[Unit] = tell(s"Info - $msg")
      def logWarn(msg: String): UIO[Unit] = tell(s"Warn - $msg")
      def logError(msg: String): UIO[Unit] = tell(s"Error - $msg")
    }

    object Live extends Live

    def logInfo(msg: String): ZIO[Logging, Nothing, Unit] = ZIO.accessM(_.logging.logInfo(msg))
    def logWarn(msg: String): ZIO[Logging, Nothing, Unit] = ZIO.accessM(_.logging.logWarn(msg))
    def logError(msg: String): ZIO[Logging, Nothing, Unit] = ZIO.accessM(_.logging.logError(msg))

  }

  trait CurrencyApiZ {
    def currencyApi: CurrencyApiZ.Service
  }

  object CurrencyApiZ {

    trait Service {
      val allCurrencies: TaskR[ConfigProvider, Set[String]]
      def exchangeRate(from: String, to: String): TaskR[ConfigProvider, BigDecimal]
    }

    trait Live extends Service {
      val allCurrencies: TaskR[ConfigProvider, Set[String]] = ZIO.accessM(provider => ZIO.fromFuture(_ => CurrencyApi.allCurrencies(provider.service.config)))
      def exchangeRate(from: String, to: String): TaskR[ConfigProvider, BigDecimal] = ZIO.accessM(provider => ZIO.fromFuture(implicit ec => CurrencyApi.exchangeRate(from, to)(provider.service.config).map(_.getOrElse(throw new Exception("")))))
    }

    object Live extends Live

    val allCurrencies: ZIO[ConfigProvider with CurrencyApiZ, Throwable, Set[String]] = ZIO.accessM(_.currencyApi.allCurrencies)
    def exchangeRate(from: String, to: String): ZIO[ConfigProvider with CurrencyApiZ, Throwable, BigDecimal] = ZIO.accessM(_.currencyApi.exchangeRate(from, to))

  }

}

object Application extends scalaz.zio.App with Program {
  val liveEnv = new Console with ConfigProvider with CurrencyApiZ {
    override val console: Application.Console.Service = Console.Live
    override val service: Application.ConfigProvider.Service = ConfigProvider.Live
    override val currencyApi: Application.CurrencyApiZ.Service = CurrencyApiZ.Live
  }
  override def run(args: List[String]): ZIO[Application.Environment, Nothing, Int] = program.provide(liveEnv).fold(_ => 1, _ => 0)
}