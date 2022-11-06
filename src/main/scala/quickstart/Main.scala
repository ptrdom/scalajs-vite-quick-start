package quickstart

import org.scalajs.dom.document
import quickstart.Counter.setupCounter

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("./style.css", JSImport.Namespace)
object Style extends js.Object

@js.native
@JSImport("./javascript.svg", JSImport.Default)
object JavascriptLogo extends js.Object

object Main extends App {
  val style = Style
  val javascriptLogo = JavascriptLogo

  document.querySelector("#app").innerHTML = s"""
      |  <div>
      |    <a href="https://vitejs.dev" target="_blank">
      |      <img src="/vite.svg" class="logo" alt="Vite logo" />
      |    </a>
      |    <a href="https://developer.mozilla.org/en-US/docs/Web/JavaScript" target="_blank">
      |      <img src="${javascriptLogo}" class="logo vanilla" alt="JavaScript logo" />
      |    </a>
      |    <h1>Hello Vite!</h1>
      |    <div class="card">
      |      <button id="counter" type="button"></button>
      |    </div>
      |    <p class="read-the-docs">
      |      Click on the Vite logo to learn more
      |    </p>
      |  </div>
      |""".stripMargin

  setupCounter(document.querySelector("#counter"))
}
