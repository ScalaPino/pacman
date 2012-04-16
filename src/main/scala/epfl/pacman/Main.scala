package epfl.pacman

import java.awt.Color
import scala.swing.MainFrame
import scala.swing.SimpleSwingApplication

object Main extends SimpleSwingApplication {

  val mvc = new maze.MVC

  def top = new MainFrame {
      title = mvc.text("titleText")
      background = Color.BLACK
      contents = mvc.gui
      maximize()
  }

  mvc.controller.start()
  Thread.sleep(1000)
  mvc.ticker.start()

}
