package quickstart

import org.scalajs.dom
import org.scalajs.dom.Element

object Counter {

  def setupCounter(element: Element) = {
    var counter = 0
    val setCounter = (count: Int) => {
      counter = count
      element.innerHTML = s"count is ${counter}"
    }
    element.addEventListener(
      "click",
      (_: dom.Event) => {
        counter += 1
        setCounter(counter)
      }
    )
    setCounter(0)
  }
}
