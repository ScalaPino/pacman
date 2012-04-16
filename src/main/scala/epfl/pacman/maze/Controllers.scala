package epfl.pacman
package maze

import actors.Actor
import java.awt.Rectangle
import scala.util.Random.nextInt
import behaviour.{DefaultMonsterBehavior, DefaultPacManBehavior, Behavior}
import interfac._

trait Controllers { mvc: MVC =>

  class Controller extends Actor {
    private var pacmanBehavior: Behavior { val mvc: Controllers.this.type } = new DefaultPacManBehavior {
      val mvc: Controllers.this.type = Controllers.this
    }

    private val monsterBehavior = new DefaultMonsterBehavior {
      val mvc: Controllers.this.type = Controllers.this
    }

    private def bonus = model.counters('bonus)
    private def bonus_=(v: Int) { model.counters('bonus) = v }
    private def score = model.counters('score)
    private def score_=(v: Int) { model.counters('score) = v }
    private def tickCounter = model.counters('tick)
    private def tickCounter_=(v: Int) { model.counters('tick) = v }
    private def hunterCounter = model.counters('hunter)
    private def hunterCounter_=(v: Int) { model.counters('hunter) = v }
    private def timeCounter = model.counters('time)
    private def timeCounter_=(v: Int) { model.counters('time) = v }
    private object revivals {
      def apply(m: Monster) = model.counters(m)
      def update(m: Monster, v: Int) = model.counters(m) = v
    }

    private def makeOffsetPosition(to: Position, dir: Direction, stopped: Boolean) = {
      val s = Settings.blockSize

      val (xo, yo) = if(!stopped) {
        dir match {
          case Up    => (0, s)
          case Left  => (s, 0)
          case Down  => (0, -s)
          case Right => (-s, 0)
        }
      } else {
        (0, 0)
      }

      @inline def donut(i: Int, s: Int) = (i + s) % s
      OffsetPosition(donut(to.x, Settings.hBlocks), donut(to.y, Settings.vBlocks), xo, yo)
    }

    @inline private def figureRect(f: Figure) = {
      val pos = f.pos
      val s = Settings.blockSize
      new Rectangle(pos.x*s + pos.xo - 1, pos.y*s + pos.yo - 1, s + 2, s + 2)
    }

    /**
     * Returns
     *  - new position (old one if he's trying to go into a wall)
     *  - new direction (old one if goint to a wall)
     *  - boolean indicating wether he stopped
     */
    def validateDir(model: Model, f: Figure, optDir: Option[Direction]): (Position, Direction, Boolean) = {
      // Make sure the direction is possible
      optDir match {
        case Some(dir) if !model.isWallAt(f.pos.nextIn(dir)) =>
          (f.pos.nextIn(dir), dir, false)
        case _ =>
          (f.pos, f.dir, true)
      }
    }

    def act() {
      loop {
        react {
          case Tick =>
            if (model.state == Running) {

              /**
               * MAJOR TICK TASKS
               *  - eat points
               *  - compute new pacman position
               *  - compute new monster positions
               *  - revive dead monsters
               */

              var modelWithOldPacmanPosition = model

              // in hunter mode, we need to compute pacman's position more often
              if (tickCounter == 0 || (tickCounter == Settings.blockSize/2 && model.pacman.hunter)) {

                if (!model.simpleMode) {
                  if (hunterCounter > 0)
                    hunterCounter -= 1

                  val p = model.points.find(p => p.pos == model.pacman.pos)

                  // eat points before computing new position and making pacman hunted
                  if (!p.isEmpty) {
                    if (p.get.isInstanceOf[SuperPoint]) {
                      score += 20
                      if (!model.pacman.hunter) {
                        bonus = 100
                      }
                      model = model.copy(pacman = model.pacman.copy(hunter = true))
                      hunterCounter = Settings.ticksToHunt
                    } else {
                      score += 10
                    }
                    model = model.copy(points = model.points - p.get)

                    if (model.points.isEmpty) {
                      model = model.copy(state = GameWon(Settings.restartTime))
                      new SoundPlayer("success.wav").start()
                    }
                    gui.update()
                  }

                  // make pacman hunted (only do it on major tick: otherwise pacman might make a jump for half a box)
                  if (model.pacman.hunter && hunterCounter == 0 && tickCounter == 0) {
                    model = model.copy(pacman = model.pacman.copy(hunter = false))
                  }
                }

                // fairness: pacman knows he just ate a cherry, so monsters also should know that.
                // however, pacman doesn't know the monster's new position, so the monsters also don't
                // know pacman's.
                modelWithOldPacmanPosition = model

                // update pacman's position
                val (pos, dir, stopped) = validateDir(model, model.pacman, pacmanBehavior.next(model, model.pacman))
                model = model.copy(pacman = model.pacman.copy(makeOffsetPosition(pos, dir, stopped), dir, stopped))
              }


              // major tick compute the monster's new position, revive dead monsters
              if (tickCounter == 0) {
                // reset the tick counter
                tickCounter = Settings.blockSize

                // compute next block position if all the small steps have been painted
                var newMonsters = model.monsters.map(monster => {
                  // use the model with pacman's old position for computing the monster's next position (more fair!)
                  val (pos, dir, stopped) = validateDir(model, monster, monsterBehavior.next(modelWithOldPacmanPosition, monster))
                  val animMode  = (model.minDistBetween(monster.pos, monster.pos, model.pacman.pos, Set[Position]()) < 10) && !model.pacman.hunter
                  monster.copy(pos = makeOffsetPosition(pos, dir, stopped),
                               dir = dir,
                               stopped = stopped,
                               anim = monster.anim.copy(status = animMode))
                })

                var newDeadMonsters = model.deadMonsters
                for (m <- model.deadMonsters) {
                  revivals(m) -= 1
                  if (revivals(m) == 0) {
                    val pos = model.randomValidPos
                    newMonsters += m.copy(pos = makeOffsetPosition(pos, Right, false),
                                          dir = Right,
                                          stopped = false,
                                          anim = m.anim.copy(status = false))
                    newDeadMonsters -= m
                  }
                }

                model = model.copy(monsters = newMonsters, deadMonsters = newDeadMonsters)
              }


              /**
               * MINOR TICK TASKS
               *  - update pacman's offset
               *  - update monster's offsets
               *  - compute collisions
               *  - check if time is over
               */

              tickCounter -= 1

              // update pacman
              if (!model.pacman.stopped) {
                model.pacman.incrOffset
                if (model.pacman.hunter)
                  model.pacman.incrOffset
              }
              model.pacman.incrAngle
              view.repaint(figureRect(model.pacman))


              // update monsters
              for (monster <- model.monsters) {
                if (!monster.stopped) {
                  monster.incrOffset
                }
                monster.incrAnimOffset
                view.repaint(figureRect(monster))
              }


              // Repaint borders so that the donut doesn't leave traces
              val s = Settings.blockSize
              view.repaint(new Rectangle(0, 0, s, Settings.vBlocks*s))
              view.repaint(new Rectangle((Settings.hBlocks-1)*s, 0, Settings.hBlocks*s, Settings.vBlocks*s))


              // compute collisions
              val omonst = model.monsters.find(m => { m.pos.overlaps(model.pacman.pos) })
              if (!omonst.isEmpty) {
                if (model.pacman.hunter) {
                  // eat the monster
                  score += bonus
                  bonus *= 2
                  model = model.copy(monsters = model.monsters - omonst.get, deadMonsters = model.deadMonsters + omonst.get)
                  revivals(omonst.get) = Settings.ticksToRevive
                } else {
                  // monster eats pacman
                  if (model.simpleMode) {
                    model = model.copy(state = GameOver(Settings.restartTime))
                    new SoundPlayer("dead.wav").start()
                  } else {
                    model = model.copy(pacman = model.pacman.copy(lives = model.pacman.lives-1))
                    if (model.pacman.lives > 0)
                      model = model.copy(state = LifeLost(Settings.dieTime))
                    else
                      model = model.copy(state = GameOver(Settings.restartTime))

                    new SoundPlayer("dead.wav").start()
                  }
                }
                gui.update()
              }


              // update time to win
              if (model.simpleMode) {
                timeCounter -= 1
                gui.statusDisplay.repaint()
                if (timeCounter == 0) {
                  model = model.copy(state = GameWon(Settings.restartTime))
                  new SoundPlayer("success.wav").start()
                  gui.update()
                }
              }


            } else {
              // simulation is not running, update game countdown, die conuter

              model.state match {
                case l @ LifeLost(_) =>
                  l.delay -= 1
                  if (l.delay == 0) {
                    if (!model.simpleMode && model.pacman.lives > 0) {
                      model = model.resetFigures().copy(state = Running)
                      view.repaint()
                    }
                  }

                case o @ GameOver(_) if Settings.demoMode =>
                  o.delay -= 1
                  if (o.delay == 0) {
                    controller ! Reset(model.simpleMode)
                  }

                case o @ GameWon(_) if Settings.demoMode =>
                  o.delay -= 1
                  if (o.delay == 0) {
                    controller ! Reset(model.simpleMode)
                  }

                case _ => ()
              }
            }


          case Pause =>
            if (model.state == Running) {
              model = model.copy(state = Paused)
              gui.update()
            } else if (model.state == Paused) {
              model = model.copy(state = Running)
              gui.update()
            }

//          case Compile(code) =>
//            val next = model.state match {
//              case CompileError(n) => n
//              case s => s
//            }
//            model = model.copy(state = Loading(next))
//            gui.update()
//            compiler.compile(code)


          case FoundErrors(lines) =>
            gui.setErrors(lines)
            model.state match {
              case Loading(next) =>
                model = model.copy(state = CompileError(next))
            }
            gui.update()

          case Load(newPacmanBehavior) =>
            pacmanBehavior = newPacmanBehavior
            val Loading(next) = model.state
            model = model.copy(state = next)
            next match {
              case GameOver(_) | GameWon(_) =>
                controller ! Reset(model.simpleMode)
              case Paused =>
                controller ! Pause
              case _ =>
                gui.update()

            }

          case Reset(simpleMode) =>
            model.state match {
              case Loading(_) | CompileError(_) => ()
              case _ =>
                val points: Set[Thingy] = if (simpleMode) Set()
                                          else ModelDefaults.points
                model = new Model(simpleMode = simpleMode, points = points)
            }
            gui.update()
        }
      }
    }
  }

  object ticker extends Actor {
    def act() {
      while(true) {
        controller ! Tick
        Thread.sleep(Settings.sleepTime)
      }
    }
  }


  case object Tick
  case object Pause
  case class Reset(simpleMode: Boolean)
  case class Compile(code: String)
  case class FoundErrors(lines: collection.Set[Int])
  case class Load(pacmanBehavior: Behavior { val mvc: Controllers.this.type })
}
