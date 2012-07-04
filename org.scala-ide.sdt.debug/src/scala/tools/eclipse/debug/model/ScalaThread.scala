package scala.tools.eclipse.debug.model

import scala.collection.JavaConverters.asScalaBufferConverter
import scala.tools.eclipse.debug.command.{ ScalaStepOver, ScalaStep }
import org.eclipse.debug.core.model.{ IThread, IBreakpoint }
import com.sun.jdi.ThreadReference
import com.sun.jdi.VMDisconnectedException
import com.sun.jdi.ObjectCollectedException
import org.eclipse.debug.core.DebugEvent
import com.sun.jdi.Value
import com.sun.jdi.ObjectReference
import com.sun.jdi.Method
import scala.tools.eclipse.debug.command.ScalaStepInto
import scala.tools.eclipse.debug.command.ScalaStepReturn
import scala.actors.Actor
import scala.actors.Actor.State
import scala.actors.Future

object ScalaThread {
  def apply(target: ScalaDebugTarget, thread: ThreadReference): ScalaThread = {
    val actor = new ScalaThreadActor(thread)
    val scalaThread = new ScalaThread(actor, target, thread)
    actor.start(scalaThread)
    scalaThread.fireCreationEvent
    scalaThread
  }
}

class ScalaThread private (eventActor: ScalaThreadActor, target: ScalaDebugTarget, private[model] val thread: ThreadReference) extends ScalaDebugElement(target) with IThread {
  import ScalaThreadActor._

  // Members declared in org.eclipse.debug.core.model.IStep

  def canStepInto(): Boolean = suspended // TODO: need real logic
  def canStepOver(): Boolean = suspended // TODO: need real logic
  def canStepReturn(): Boolean = suspended // TODO: need real logic
  def isStepping(): Boolean = ???
  def stepInto(): Unit = ScalaStepInto(internalGetStackFrames.head).step

  def stepOver(): Unit = {
    // top stack frame
    ScalaStepOver(internalGetStackFrames.head).step
  }

  def stepReturn(): Unit = {
    ScalaStepReturn(internalGetStackFrames.head).step
  }

  // Members declared in org.eclipse.debug.core.model.ISuspendResume

  def canResume(): Boolean = suspended // TODO: need real logic
  def canSuspend(): Boolean = !suspended // TODO: need real logic
  def isSuspended(): Boolean = suspended // TODO: need real logic
  def resume(): Unit = {
    thread.resume
    resumeFromScala(DebugEvent.CLIENT_REQUEST)
  }
  def suspend(): Unit = {
    thread.suspend
    suspendedFromScala(DebugEvent.CLIENT_REQUEST)
  }

  // Members declared in org.eclipse.debug.core.model.IThread

  def getBreakpoints(): Array[IBreakpoint] = Array() // TODO: need real logic

  def getName(): String = {
    try {
      name = thread.name
    } catch {
      case e: ObjectCollectedException =>
        name = "<garbage collected>"
      case e: VMDisconnectedException =>
        name = "<disconnected>"
    }
    name
  }

  def getPriority(): Int = ???
  def getStackFrames(): Array[org.eclipse.debug.core.model.IStackFrame] = internalGetStackFrames.toArray
  def getTopStackFrame(): org.eclipse.debug.core.model.IStackFrame = internalGetStackFrames.headOption.getOrElse(null)
  def hasStackFrames(): Boolean = !internalGetStackFrames.isEmpty

  // event handling actor

  // ----

  // state
  @volatile
  private var suspended = false
  @volatile
  private var running = true

  // initialize name
  private var name: String = null

  val isSystemThread: Boolean = try {
    Option(thread.threadGroup).exists(_.name == "system")
  } catch {
    case e: VMDisconnectedException =>
      // some thread get created when a program terminates, and connection already closed
      false
  }

  def suspendedFromScala(eventDetail: Int) {
    eventActor !? SuspendedFromScala(eventDetail)
  }

  def resumeFromScala(eventDetail: Int) {
    eventActor ! ResumeFromScala(None, eventDetail)
  }

  def resumeFromScala(step: ScalaStep, eventDetail: Int) {
    eventActor ! ResumeFromScala(Some(step), eventDetail)
  }

  def terminatedFromScala() {
    dispose()
  }

  def invokeMethod(objectReference: ObjectReference, method: Method, args: Value*): Value = {
    val future = eventActor !! InvokeMethod(objectReference, method, args.toList)

    future.inputChannel.receive {
      case value: Value =>
        value
    }
  }

  /**
   * release all resources
   */
  def dispose() {
    running = false
    eventActor ! TerminatedFromScala
  }

  private[model] def setSuspended(state: Boolean, eventDetail: Int) {
    suspended = state
    if (suspended) {
      fireSuspendEvent(eventDetail)
    } else {
      fireResumeEvent(eventDetail)
    }
  }
  
  /**
   * Return the current list of stack frames, using the actor system
   */
  private def internalGetStackFrames(): List[ScalaStackFrame] = {
    if (running) {
      (eventActor !! GetStackFrames).asInstanceOf[Future[List[ScalaStackFrame]]]()
    } else {
      Nil
    }    
  }

}

private[model] object ScalaThreadActor {
  case class SuspendedFromScala(eventDetail: Int)
  case class ResumeFromScala(step: Option[ScalaStep], eventDetail: Int)
  case class InvokeMethod(objectReference: ObjectReference, method: Method, args: List[Value])
  case object TerminatedFromScala
  case object GetStackFrames
}

private[model] class ScalaThreadActor(thread: ThreadReference) extends Actor {
  import ScalaThreadActor._

  // step management
  private var currentStep: Option[ScalaStep] = None

  private var scalaThread: ScalaThread = _

  private var stackFrames: List[ScalaStackFrame] = Nil

  def start(st: ScalaThread) {
    scalaThread = st
    start()
  }

  def act() {
    loop {
      react {
        case SuspendedFromScala(eventDetail) =>
          import scala.collection.JavaConverters._
          currentStep.foreach(_.stop)
          currentStep = None
          stackFrames = thread.frames.asScala.map(new ScalaStackFrame(scalaThread, _)).toList
          scalaThread.setSuspended(true, eventDetail)
          reply(None)
        case ResumeFromScala(step, eventDetail) =>
          currentStep = step
          stackFrames = Nil
          scalaThread.setSuspended(false, eventDetail)
          thread.resume
        case InvokeMethod(objectReference, method, args) =>
          if (!scalaThread.isSuspended) {
            throw new Exception("Not suspended")
          } else {
            import scala.collection.JavaConverters._
            val result = objectReference.invokeMethod(thread, method, args.asJava, ObjectReference.INVOKE_SINGLE_THREADED)
            // update the stack frames
            thread.frames.asScala.iterator.zip(stackFrames.iterator).foreach(
              v => v._2.rebind(v._1))
            reply(result)
          }
        case TerminatedFromScala =>
          currentStep.foreach(_.stop)
          currentStep = None
          stackFrames = Nil
          scalaThread.fireTerminateEvent
          exit
        case GetStackFrames =>
          reply(stackFrames)
      }
    }
  }

}