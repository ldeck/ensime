/**
 *  Copyright (c) 2010, Aemon Cannon
 *  All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *      * Redistributions of source code must retain the above copyright
 *        notice, this list of conditions and the following disclaimer.
 *      * Redistributions in binary form must reproduce the above copyright
 *        notice, this list of conditions and the following disclaimer in the
 *        documentation and/or other materials provided with the distribution.
 *      * Neither the name of ENSIME nor the
 *        names of its contributors may be used to endorse or promote products
 *        derived from this software without specific prior written permission.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 *  ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 *  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 *  DISCLAIMED. IN NO EVENT SHALL Aemon Cannon BE LIABLE FOR ANY
 *  DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 *  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 *  LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 *  ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 *  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.ensime.protocol

import java.io._
import org.ensime.config.{ ProjectConfig, DebugConfig, ReplConfig }
import org.ensime.debug.{ DebugUnit, DebugSourceLinePairs }
import org.ensime.model._
import org.ensime.server._
import org.ensime.util._
import org.ensime.util.SExp._
import scala.actors._
import scala.tools.nsc.util.{ Position, RangePosition }
import scala.tools.refactoring.common.Change
import scala.util.parsing.input

object SwankProtocol extends SwankProtocol {}

trait SwankProtocol extends Protocol {


  val ServerName: String = "ENSIME-ReferenceServer"
  val ProtocolVersion: String = "0.7"



  import SwankProtocol._
  import ProtocolConst._

  private var outPeer: Actor = null;
  private var rpcTarget: RPCTarget = null;

  def peer = outPeer

  def setOutputActor(peer: Actor) { outPeer = peer }

  def setRPCTarget(target: RPCTarget) { this.rpcTarget = target }

  // Handle reading / writing of messages

  def writeMessage(value: WireFormat, out: Writer) {
    val data: String = value.toWireString
    val header: String = String.format("%06x", int2Integer(data.length))
    val msg = header + data
    println("Writing: " + msg)
    out.write(msg)
    out.flush()
  }

  private def fillArray(in: java.io.Reader, a: Array[Char]) {
    var n = 0
    var l = a.length
    var charsRead = 0;
    while (n < l) {
      charsRead = in.read(a, n, l - n)
      if (charsRead == -1) {
        throw new EOFException("End of file reached in socket reader.");
      } else {
        n += charsRead
      }
    }
  }

  private val headerBuf = new Array[Char](6);

  def readMessage(in: java.io.Reader): WireFormat = {
    fillArray(in, headerBuf)
    val msglen = Integer.valueOf(new String(headerBuf), 16).intValue()
    if (msglen > 0) {
      //TODO allocating a new array each time is inefficient!
      val buf: Array[Char] = new Array[Char](msglen);
      fillArray(in, buf)
      SExp.read(new input.CharArrayReader(buf))
    } else {
      throw new IllegalStateException("Empty message read from socket!")
    }
  }

  def sendBackgroundMessage(code: Int, detail: Option[String]) {
    sendMessage(SExp(
      key(":background-message"),
      code,
      detail.map(strToSExp).getOrElse(NilAtom())))
  }

  def handleIncomingMessage(msg: Any) {
    msg match {
      case sexp: SExp => handleMessageForm(sexp)
      case _ => System.err.println("WTF: Unexpected message: " + msg)
    }
  }

  private def handleMessageForm(sexp: SExp) {
    sexp match {
      case SExpList(KeywordAtom(":swank-rpc") :: form :: IntAtom(callId) :: rest) => {
        handleEmacsRex(form, callId)
      }
      case _ => {
        sendProtocolError(ErrUnrecognizedForm, Some(sexp.toReadableString))
      }
    }
  }

  private def handleEmacsRex(form: SExp, callId: Int) {
    form match {
      case SExpList(SymbolAtom(name) :: rest) => {
        try {
          handleRPCRequest(name, form, callId)
        } catch {
          case e: Throwable =>
            {
              e.printStackTrace(System.err)
              sendRPCError(ErrExceptionInRPC, Some(e.getMessage), callId)
            }
        }
      }
      case _ => {
        sendRPCError(
          ErrMalformedRPC,
          Some("Expecting leading symbol in: " + form),
          callId)
      }
    }
  }

  /**
   * Protocol Change Log:
   *
   *   Version 0.7:
   *     Rename swank:perform-refactor to swank:prepare-refactor.
   *     Include status flag in return of swank:exec-refactor.
   *
   */


  /**
   * Doc DataStructure:
   *   Position
   * Summary:
   *   A source position.
   * Structure:
   *   (
   *     :file   //String:A filename
   *     :offset  //Int:The zero-indexed character offset of this position.
   *   )
   */

  /**
   * Doc DataStructure:
   *   RangePosition
   * Summary:
   *   A source position that describes a range of characters in a file.
   * Structure:
   *   (
   *     :file   //String:A filename
   *     :start  //Int:The character offset of the start of the range.
   *     :end    //Int:The character offset of the end of the range.
   *   )
   */

  /**
   * Doc DataStructure:
   *   Change
   * Summary:
   *   Describes a change to a source code file.
   * Structure:
   *   (
   *   :file //String:Filename of source  to be changed
   *   :text //String:Text to be inserted
   *   :from //Int:Character offset of start of text to replace.
   *   :to //Int:Character offset of end of text to replace.
   *   )
   */

  /**
   * Doc DataStructure:
   *   SymbolSearchResult
   * Summary:
   *   Describes a symbol found in a search operation.
   * Structure:
   *   (
   *   :name //String:Qualified name of symbol.
   *   :local-name //String:Unqualified name of symbol
   *   :decl-as //Symbol:What kind of symbol this is.
   *   :pos //Position:Where is this symbol declared?
   *   )
   */

  /**
   * Doc DataStructure:
   *   ParamSectionInfo
   * Summary:
   *   Description of one of a method's parameter sections.
   * Structure:
   *   (
   *   :params //List of (String TypeInfo) pairs:Describes params in section
   *   :is-implicit //Bool:Is this an implicit parameter section.
   *   )
   */

  /**
   * Doc DataStructure:
   *   CallCompletionInfo
   * Summary:
   *   Description of a Scala method's type
   * Structure:
   *   (
   *   :result-type //TypeInfo
   *   :param-sections //List of ParamSectionInfo:
   *   )
   */

  /**
   * Doc DataStructure:
   *   TypeMemberInfo
   * Summary:
   *   Description of a type member
   * Structure:
   *   (
   *   :name //String:The name of this member.
   *   :type-sig //String:The type signature of this member
   *   :type-id //Int:The type id for this member's type.
   *   :is-callable //Bool:Is this a function or method type.
   *   )
   */

  /**
   * Doc DataStructure:
   *   TypeInfo
   * Summary:
   *   Description of a Scala type.
   * Structure:
   *   (
   *   :name //String:The short name of this type.
   *   :type-id //Int:Type Id of this type (for fast lookups)
   *   :full-name //String:The qualified name of this type
   *   :decl-as //Symbol:What kind of type is this? (class,trait,object, etc)
   *   :type-args //List of TypeInfo:Type args this type has been applied to.
   *   :members //List of TypeMemberInfo
   *   :arrow-type //Bool:Is this a function or method type?
   *   :result-type //TypeInfo:
   *   :param-sections //List of ParamSectionInfo:
   *   :pos //Position:Position where this type was declared
   *   :outer-type-id //Int:If this is a nested type, type id of owning type
   *   )
   */

  /**
   * Doc DataStructure:
   *   InterfaceInfo
   * Summary:
   *   Describes an inteface that a type supports
   * Structure:
   *   (
   *   :type //TypeInfo:The type of the interface.
   *   :via-view //Bool:Is this type suppoted via an implicit conversion?
   *   )
   */

  /**
   * Doc DataStructure:
   *   TypeInspectInfo
   * Summary:
   *   Detailed description of a Scala type.
   * Structure:
   *   (
   *   :type //TypeInfo
   *   :companion-id //Int:Type Id of this type's companion type.
   *   :interfaces //List of InterfaceInfo:Interfaces this type supports
   *   )
   */

  /**
   * Doc DataStructure:
   *   SymbolInfo
   * Summary:
   *   Description of a Scala symbol.
   * Structure:
   *   (
   *   :name //String:Name of this symbol.
   *   :type //TypeInfo:The type of this symbol.
   *   :decl-pos //Position:Source location of this symbol's declaration.
   *   :is-callable //Bool:Is this symbol a method or function?
   *   )
   */

  /**
   * Doc DataStructure:
   *   SymbolInfoLight
   * Summary:
   *   An abbreviated symbol description.
   * Structure:
   *   (
   *   :name //String:Name of this symbol.
   *   :type-sig //String:The type signature of this symbol.
   *   :type-id //Int:A type id.
   *   :is-callable //Bool:Is this symbol a method or function?
   *   )
   */

  /**
   * Doc DataStructure:
   *   PackageInfo
   * Summary:
   *   A description of a package and all its members.
   * Structure:
   *   (
   *   :name //String:Name of this package.
   *   :full-name //String:Qualified name of this package.
   *   :members //List of PackageInfo | TypeInfo:The members of this package.
   *   :info-type //Symbol:Literally 'package
   *   )
   */

  /**
   * Doc DataStructure:
   *   SymbolDesignations
   * Summary:
   *   Describe the symbol classes in a given textual range.
   * Structure:
   *   (
   *   :file //String:Filename of file to be annotated.
   *   :syms //List of (Symbol Integer Integer):Denoting the symbol class and start and end of the range where it applies.
   *   )
   */

  /**
   * Doc DataStructure:
   *   PackageMemberInfoLight
   * Summary:
   *   An abbreviated package member description.
   * Structure:
   *   (
   *   :name //String:Name of this symbol.
   *   )
   */

  /**
   * Doc DataStructure:
   *   RefactorFailure
   * Summary:
   *   Notification that a refactoring has failed in some way.
   * Structure:
   *   (
   *   :procedure-id //Int:The id for this refactoring.
   *   :message //String:A text description of the error.
   *   :status //Symbol:'failure.
   *   )
   */

  /**
   * Doc DataStructure:
   *   RefactorEffect
   * Summary:
   *   A description of the effects a proposed refactoring would have.
   * Structure:
   *   (
   *   :procedure-id //Int:The id for this refactoring.
   *   :refactor-type //Symbol:The type of refactoring.
   *   :status //Symbol:'success
   *   :changes //List of Change:The textual effects.
   *   )
   */

  /**
   * Doc DataStructure:
   *   RefactorResult
   * Summary:
   *   A description of the effects a refactoring has effected.
   * Structure:
   *   (
   *   :procedure-id //Int:The id for this refactoring.
   *   :refactor-type //Symbol:The type of refactoring.
   *   :touched //List of String:Names of files touched by the refactoring.
   *   :status //Symbol:'success.
   *   )
   */

  private def handleRPCRequest(callType: String, form: SExp, callId: Int) {

    println("\nHandling RPC: " + form)

    def oops = sendRPCError(ErrMalformedRPC,
      Some("Malformed " + callType + " call: " + form), callId)

    callType match {

      /**
       * Doc:
       *   swank:connection-info
       * Summary:
       *   Request connection information.
       * Arguments:
       *   None
       * Return:
       *   (
       *   :pid  //Int:The integer process id of the server (or nil if unnavailable)
       *   :implementation
       *     (
       *     :name  //String:An identifying name for this server implementation.
       *     )
       *     :version //String:The version of the protocol this server supports.
       *   )
       * Example call:
       *   (:swank-rpc (swank:connection-info) 42)
       * Example return:
       *   (:return (:ok (:pid nil :implementation (:name "ENSIME - Reference Server")
       *   :version "0.7")) 42)
       */
      case "swank:connection-info" => {
        sendConnectionInfo(callId)
      }

      /**
       * Doc:
       *   swank:init-project
       * Summary:
       *   Initialize the server with a project configuration. The
       *   server returns it's own knowledge about the project, including
       *   source roots which can be used by clients to determine whether
       *   a given source file belongs to this project.
       * Arguments:
       *   A complete ENSIME configuration property list. See manual.
       * Return:
       *   (
       *   :project-name //String:The name of the project.
       *   :source-roots //List of Strings:The source code directory roots..
       *   )
       * Example call:
       *   (:swank-rpc (swank:init-project (:use-sbt t :compiler-args
       *   (-Ywarn-dead-code -Ywarn-catches -Xstrict-warnings)
       *   :root-dir /Users/aemon/projects/ensime/)) 42)
       * Example return:
       *   (:return (:ok (:project-name "ensime" :source-roots
       *   ("/Users/aemon/projects/ensime/src/main/scala"
       *   "/Users/aemon/projects/ensime/src/test/scala"
       *   "/Users/aemon/projects/ensime/src/main/java"))) 42)
       */
      case "swank:init-project" => {
        form match {
          case SExpList(head :: (conf: SExpList) :: body) => {
            val config = ProjectConfig.fromSExp(conf)
            rpcTarget.rpcInitProject(config, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:peek-undo
       * Summary:
       *   The intention of this call is to preview the effect of an undo
       *   before executing it.
       * Arguments:
       *   None
       * Return:
       *   (
       *   :id //Int:Id of this undo
       *   :changes //List of Changes:Describes changes this undo would effect.
       *   :summary //String:Summary of action this undo would revert.
       *   )
       * Example call:
       *   (:swank-rpc (swank:peek-undo) 42)
       * Example return:
       *   (:return (:ok (:id 1 :changes ((:file
       *   "/ensime/src/main/scala/org/ensime/server/RPCTarget.scala"
       *   :text "rpcInitProject" :from 2280 :to 2284))
       *   :summary "Refactoring of type: 'rename") 42)
       */
      case "swank:peek-undo" => {
        rpcTarget.rpcPeekUndo(callId)
      }

      /**
       * Doc:
       *   swank:exec-undo
       * Summary:
       *   Execute a specific, server-side undo operation.
       * Arguments:
       *   An integer undo id. See swank:peek-undo for how to learn this id.
       * Return:
       *   (
       *   :id //Int:Id of this undo
       *   :touched-files //List of Strings:Filenames of touched files,
       *   )
       * Example call:
       *   (:swank-rpc (swank:exec-undo 1) 42)
       * Example return:
       *   (:return (:ok (:id 1 :touched-files
       *   ("/src/main/scala/org/ensime/server/RPCTarget.scala"))) 42)
       */
      case "swank:exec-undo" => {
        form match {
          case SExpList(head :: (IntAtom(id)) :: body) => {
            rpcTarget.rpcExecUndo(id, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:repl-config
       * Summary:
       *   Get information necessary to launch a scala repl for this project.
       * Arguments:
       *   None
       * Return:
       *   (
       *   :classpath //String:Classpath string formatted for passing to Scala.
       *   )
       * Example call:
       *   (:swank-rpc (swank:repl-config) 42)
       * Example return:
       *   (:return (:ok (:classpath "lib1.jar:lib2.jar:lib3.jar")) 42)
       */
      case "swank:repl-config" => {
        rpcTarget.rpcReplConfig(callId)
      }

      /**
       * Doc:
       *   swank:builder-init
       * Summary:
       *   Initialize the incremental builder and kick off a full rebuild.
       * Arguments:
       *   None
       * Return:
       *   None
       * Example call:
       *   (:swank-rpc (swank:builder-init) 42)
       * Example return:
       *   (:return (:ok ()) 42)
       */
      case "swank:builder-init" => {
        rpcTarget.rpcBuilderInit(callId)
      }

      /**
       * Doc:
       *   swank:builder-update-files
       * Summary:
       *   Signal to the incremental builder that the given files
       *   have changed and must be rebuilt. Triggers rebuild.
       * Arguments:
       *   List of Strings:Filenames, absolute or relative to the project root.
       * Return:
       *   None
       * Example call:
       *   (:swank-rpc (swank:builder-update-files
       *   ("/ensime/src/main/scala/org/ensime/server/Analyzer.scala")) 42)
       * Example return:
       *   (:return (:ok ()) 42)
       */
      case "swank:builder-update-files" => {
        form match {
          case SExpList(head :: SExpList(filenames) :: body) => {
            val files = filenames.map(_.toString)
            rpcTarget.rpcBuilderUpdateFiles(files, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:builder-add-files
       * Summary:
       *   Signal to the incremental builder that the given files
       *   should be added to the build. Triggers rebuild.
       * Arguments:
       *   List of Strings:Filenames, absolute or relative to the project root.
       * Return:
       *   None
       * Example call:
       *   (:swank-rpc (swank:builder-add-files
       *   ("/ensime/src/main/scala/org/ensime/server/Analyzer.scala")) 42)
       * Example return:
       *   (:return (:ok ()) 42)
       */
      case "swank:builder-add-files" => {
        form match {
          case SExpList(head :: SExpList(filenames) :: body) => {
            val files = filenames.map(_.toString)
            rpcTarget.rpcBuilderAddFiles(files, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:builder-remove-files
       * Summary:
       *   Signal to the incremental builder that the given files
       *   should be removed from the build. Triggers rebuild.
       * Arguments:
       *   List of Strings:Filenames, absolute or relative to the project root.
       * Return:
       *   None
       * Example call:
       *   (:swank-rpc (swank:builder-remove-files
       *   ("/ensime/src/main/scala/org/ensime/server/Analyzer.scala")) 42)
       * Example return:
       *   (:return (:ok ()) 42)
       */
      case "swank:builder-remove-files" => {
        form match {
          case SExpList(head :: SExpList(filenames) :: body) => {
            val files = filenames.map(_.toString)
            rpcTarget.rpcBuilderRemoveFiles(files, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:debug-config
       * Summary:
       *   Retrieve information necessary for starting a debugger. Also triggers
       *   the creation of internal debug datastructures (mappings between Scala
       *   source locations and mangled Java names)
       * Arguments:
       *   None
       * Return:
       *   (
       *   :classpath //String:Classpath string formatted for passing to JDB.
       *   :sourcepath //String:Sourcepath string formatted for passing to JDB.
       *   )
       * Example call:
       *   (:swank-rpc (swank:debug-config) 42)
       * Example return:
       *   (:return (:ok (:classpath "lib1.jar:lib2.jar:lib3.jar" :sourcepath
       *   "/ensime/src/main/scala:/other/misc/src") 42)
       */
      case "swank:debug-config" => {
        rpcTarget.rpcDebugConfig(callId)
      }

      /**
       * Doc:
       *   swank:debug-unit-info
       * Summary:
       *   Request the mangled Java name of the compilation unit that
       *   results from the Scala code at the given source location.
       * Arguments:
       *   String:The filename of the Scala source location.
       *   Int:A zero-indexed character offset into the file.
       * Return:
       *   (
       *   :full-name //String:The qualified name of the unit.
       *   :package //String:The package of the unit.
       *   :start-line //Int:Source line stored in debug info of .class file.
       *   :end-line //Int:Source line stored in debug info of .class file.
       *   )
       * Example call:
       *   (:swank-rpc (swank:debug-unit-info "Server.scala" 47 ) 42)
       * Example return:
       *   (:return (:ok (:full-name "org.ensime.server.Server$" :package ""
       *    :start-line 37 :end-line 103)) 42)
       */
      case "swank:debug-unit-info" => {
        form match {
          case SExpList(head :: StringAtom(sourceName) ::
            IntAtom(line) :: StringAtom(packPrefix) :: body) => {
            rpcTarget.rpcDebugUnitInfo(sourceName, line, packPrefix, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:debug-class-locs-to-source-locs
       * Summary:
       *   Map from a Java location that the debugger refers to, to
       *   a Scala source location.
       * Arguments:
       *   List of (String Int):The String is a filename and Int is a line in
       *     that file.
       * Return:
       *   List of (String Int):The String is a filename and Int is a line in
       *     that file.
       * Example call:
       *   (:swank-rpc (swank:debug-class-locs-to-source-locs
       *   (("org.ensime.server.Server$" 49))) 42)
       * Example return:
       *   (:return (:ok (("Server.scala" 49))) 42)
       */
      case "swank:debug-class-locs-to-source-locs" => {
        form match {
          case SExpList(head :: SExpList(pairs) :: body) => {
            val nameLinePairs = pairs.flatMap {
              case SExpList((classname: StringAtom) :: (line: IntAtom) :: body) => {
                Some(classname.toString, line.value)
              }
              case _ => Some("", -1)
            }
            rpcTarget.rpcDebugClassLocsToSourceLocs(nameLinePairs, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:remove-file
       * Summary:
       *   Remove a file from consideration by the ENSIME analyzer.
       * Arguments:
       *   String:A filename, absolute or relative to the project.
       * Return:
       *   None
       * Example call:
       *   (:swank-rpc (swank:remove-file "Analyzer.scala") 42)
       * Example return:
       *   (:return (:ok t) 42)
       */
      case "swank:remove-file" => {
        form match {
          case SExpList(head :: StringAtom(file) :: body) => {
            rpcTarget.rpcRemoveFile(file, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:typecheck-file
       * Summary:
       *   Request immediate load and check the given source file.
       * Arguments:
       *   String:A filename, absolute or relative to the project.
       * Return:
       *   None
       * Example call:
       *   (:swank-rpc (swank:typecheck-file "Analyzer.scala") 42)
       * Example return:
       *   (:return (:ok t) 42)
       */
      case "swank:typecheck-file" => {
        form match {
          case SExpList(head :: StringAtom(file) :: body) => {
            rpcTarget.rpcTypecheckFile(file, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:typecheck-all
       * Summary:
       *   Request immediate load and typecheck of all known sources.
       * Arguments:
       *   None
       * Return:
       *   None
       * Example call:
       *   (:swank-rpc (swank:typecheck-all) 42)
       * Example return:
       *   (:return (:ok t) 42)
       */
      case "swank:typecheck-all" => {
        rpcTarget.rpcTypecheckAll(callId)
      }

      /**
       * Doc:
       *   swank:format-source
       * Summary:
       *   Run the source formatter the given source files. Writes
       *   the formatted sources to the disk. Note: the client is
       *   responsible for reloading the files from disk to display
       *   to user.
       * Arguments:
       *   List of String:Filenames, absolute or relative to the project.
       * Return:
       *   None
       * Example call:
       *   (:swank-rpc (swank:format-source ("/ensime/src/Test.scala")) 42)
       * Example return:
       *   (:return (:ok t) 42)
       */
      case "swank:format-source" => {
        form match {
          case SExpList(head :: SExpList(filenames) :: body) => {
            val files = filenames.map(_.toString)
            rpcTarget.rpcFormatFiles(files, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:public-symbol-search
       * Summary:
       *   Search top-level symbols (types and methods) for names that
       *   contain ALL the given search keywords.
       * Arguments:
       *   List of Strings:Keywords that will be ANDed to form the query.
       *   Int:Maximum number of results to return.
       * Return:
       *   List of SymbolSearchResults
       * Example call:
       *   (:swank-rpc (swank:public-symbol-search ("java" "io" "File") 50) 42)
       * Example return:
       *   (:return (:ok ((:name "java.io.File" :local-name "File" :decl-as class
       *   :pos (:file "/Classes/classes.jar" :offset -1))) 42)
       */
      case "swank:public-symbol-search" => {
        form match {
          case SExpList(head :: SExpList(keywords) :: IntAtom(maxResults) :: body) => {
            rpcTarget.rpcPublicSymbolSearch(
              keywords.map(_.toString).toList, maxResults, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:import-suggestions
       * Summary:
       *   Search top-level types for qualified names similar to the given
       *   type names. This call can service requests for many typenames at
       *   once, but this isn't currently used in ENSIME.
       * Arguments:
       *   String:Source filename, absolute or relative to the project.
       *   Int:Character offset within that file where type name is referenced.
       *   List of String:Type names (possibly partial) for which to suggest.
       *   Int:The maximum number of results to return.
       * Return:
       *   List of Lists of SymbolSearchResults:Each list corresponds to one of the
       *     type name arguments.
       * Example call:
       *   (:swank-rpc (swank:import-suggestions
       *   "/ensime/src/main/scala/org/ensime/server/Analyzer.scala"
       *   2300 (Actor) 10) 42)
       * Example return:
       *   (:return (:ok (((:name "scala.actors.Actor" :local-name "Actor"
       *   :decl-as trait :pos (:file "/lib/scala-library.jar" :offset -1)))))
       *   42)
       */
      case "swank:import-suggestions" => {
        form match {
          case SExpList(head :: StringAtom(file) :: IntAtom(point) ::
            SExpList(names) :: IntAtom(maxResults) :: body) => {
            rpcTarget.rpcImportSuggestions(file, point,
              names.map(_.toString).toList, maxResults, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:scope-completion
       * Summary:
       *   Find possible completions for a name in the given scope.
       * Arguments:
       *   String:Source filename, absolute or relative to the project.
       *   Int:Character offset within that file.
       *   String:The prefix of the name we are looking for.
       *   Bool:Are we looking for a constructor? (a name following 'new').
       * Return:
       *   List of SymbolInfoLight: The possible completions.
       * Example call:
       *   (:swank-rpc (swank:scope-completion
       *   "/ensime/src/main/scala/org/ensime/protocol/SwankProtocol.scala
       *   22624 "fo" nil) 42)
       * Example return:
       *   (:return (:ok ((:name "form" :type-sig "SExp" :type-id 10)
       *   (:name "format" :type-sig "(String, <repeated>[Any]) => String"
       *   :type-id 11 :is-callable t))) 42)
       */
      case "swank:scope-completion" => {
        form match {
          case SExpList(head :: StringAtom(file) :: IntAtom(point) ::
            StringAtom(prefix) :: BooleanAtom(constructor) :: body) => {
            rpcTarget.rpcScopeCompletion(file, point, prefix, constructor, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:type-completion
       * Summary:
       *   Find possible completions for a member of the object at
       *   the given point.
       * Arguments:
       *   String:A Scala source filename, absolute or relative to the project.
       *   Int:Character offset of the owning object within that file.
       *   String:The prefix of the member name we are looking for.
       * Return:
       *   List of SymbolInfoLight: The possible completions.
       * Example call:
       *   (:swank-rpc (swank:type-completion "SwankProtocol.scala"
       *   24392 "rpcTypeC") 42)
       * Example return:
       *   (:return (:ok ((:name "rpcTypeCompletion"
       *   :type-sig "(String, Int, String, Int) => Unit"
       *   :type-id 75 :is-callable t))) 42)
       */
      case "swank:type-completion" => {
        form match {
          case SExpList(head :: StringAtom(file) :: IntAtom(point) ::
            StringAtom(prefix) :: body) => {
            rpcTarget.rpcTypeCompletion(file, point, prefix, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:package-member-completion
       * Summary:
       *   Find possible completions for a given package path.
       * Arguments:
       *   String:A package path: such as "org.ensime" or "com".
       *   String:The prefix of the package member name we are looking for.
       * Return:
       *   List of PackageMemberInfoLight: List of possible completions.
       * Example call:
       *   (:swank-rpc (swank:package-member-completion org.ensime.server Server)
       *   42)
       * Example return:
       *   (:return (:ok ((:name "Server$") (:name "Server"))) 42)
       */
      case "swank:package-member-completion" => {
        form match {
          case SExpList(head :: StringAtom(path) :: StringAtom(prefix) :: body) => {
            rpcTarget.rpcPackageMemberCompletion(path, prefix, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:call-completion
       * Summary:
       *   Lookup the type information of a specific method or function
       *   type. This is used by ENSIME to retrieve detailed parameter
       *   and return type information after the user has selected a
       *   method or function completion.
       * Arguments:
       *   Int:A type id, as returned by swank:scope-completion or
       *     swank:type-completion.
       * Return:
       *   A CallCompletionInfo
       * Example call:
       *   (:swank-rpc (swank:call-completion 1)) 42)
       * Example return:
       *   (:return (:ok (:result-type (:name "Unit" :type-id 7 :full-name
       *   "scala.Unit" :decl-as class) :param-sections ((:params (("id"
       *   (:name "Int" :type-id 74 :full-name "scala.Int" :decl-as class))
       *   ("callId" (:name "Int" :type-id 74 :full-name "scala.Int"
       *   :decl-as class))))))) 42)
       */
      case "swank:call-completion" => {
        form match {
          case SExpList(head :: IntAtom(id) :: body) => {
            rpcTarget.rpcCallCompletion(id, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:uses-of-symbol-at-point
       * Summary:
       *   Request all source locations where indicated symbol is used in
       *   this project.
       * Arguments:
       *   String:A Scala source filename, absolute or relative to the project.
       *   Int:Character offset of the desired symbol within that file.
       * Return:
       *   List of RangePosition:Locations where the symbol is reference.
       * Example call:
       *   (:swank-rpc (swank:uses-of-symbol-at-point "Test.scala" 11334) 42)
       * Example return:
       *   (:return (:ok ((:file "RichPresentationCompiler.scala" :offset 11442
       *   :start 11428 :end 11849) (:file "RichPresentationCompiler.scala"
       *   :offset 11319 :start 11319 :end 11339))) 42)
       */
      case "swank:uses-of-symbol-at-point" => {
        form match {
          case SExpList(head :: StringAtom(file) :: IntAtom(point) :: body) => {
            rpcTarget.rpcUsesOfSymAtPoint(file, point, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:type-by-id
       * Summary:
       *   Request description of the type with given type id.
       * Arguments:
       *   Int:A type id.
       * Return:
       *   A TypeIfo
       * Example call:
       *   (:swank-rpc (swank:type-by-id 1381) 42)
       * Example return:
       *   (:return (:ok (:name "Option" :type-id 1381 :full-name "scala.Option"
       *   :decl-as class :type-args ((:name "Int" :type-id 1129 :full-name "scala.Int"
       *   :decl-as class)))) 42)
       */
      case "swank:type-by-id" => {
        form match {
          case SExpList(head :: IntAtom(id) :: body) => {
            rpcTarget.rpcTypeById(id, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:type-by-name
       * Summary:
       *   Lookup a type description by name.
       * Arguments:
       *   String:The fully qualified name of a type.
       * Return:
       *   A TypeIfo
       * Example call:
       *   (:swank-rpc (swank:type-by-name "java.lang.String") 42)
       * Example return:
       *   (:return (:ok (:name "String" :type-id 1188 :full-name
       *   "java.lang.String" :decl-as class)) 42)
       */
      case "swank:type-by-name" => {
        form match {
          case SExpList(head :: StringAtom(name) :: body) => {
            rpcTarget.rpcTypeByName(name, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:type-by-name-at-point
       * Summary:
       *   Lookup a type by name, in a specific source context.
       * Arguments:
       *   String:The local or qualified name of the type.
       *   String:A source filename.
       *   Int:A character offset in the file.
       * Return:
       *   A TypeInfo
       * Example call:
       *   (:swank-rpc (swank:type-by-name-at-point "String"
       *   "SwankProtocol.scala" 31680) 42)
       * Example return:
       *   (:return (:ok (:name "String" :type-id 1188 :full-name
       *   "java.lang.String" :decl-as class)) 42)
       */
      case "swank:type-by-name-at-point" => {
        form match {
          case SExpList(head :: StringAtom(name) :: StringAtom(file) ::
            IntAtom(point) :: body) => {
            rpcTarget.rpcTypeByNameAtPoint(name, file, point, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:type-at-point
       * Summary:
       *   Lookup type of thing at given position.
       * Arguments:
       *   String:A source filename.
       *   Int:A character offset in the file.
       * Return:
       *   A TypeInfo
       * Example call:
       *   (:swank-rpc (swank:type-at-point "SwankProtocol.scala"
       *    32736) 42)
       * Example return:
       *   (:return (:ok (:name "String" :type-id 1188 :full-name
       *   "java.lang.String" :decl-as class)) 42)
       */
      case "swank:type-at-point" => {
        form match {
          case SExpList(head :: StringAtom(file) :: IntAtom(point) :: body) => {
            rpcTarget.rpcTypeAtPoint(file, point, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:inspect-type-at-point
       * Summary:
       *   Lookup detailed type of thing at given position.
       * Arguments:
       *   String:A source filename.
       *   Int:A character offset in the file.
       * Return:
       *   A TypeInspectInfo
       * Example call:
       *   (:swank-rpc (swank:inspect-type-at-point "SwankProtocol.scala"
       *    32736) 42)
       * Example return:
       *   (:return (:ok (:type (:name "SExpList$" :type-id 1469 :full-name
       *   "org.ensime.util.SExpList$" :decl-as object :pos
       *   (:file "SExp.scala" :offset 1877))......)) 42)
       */
      case "swank:inspect-type-at-point" => {
        form match {
          case SExpList(head :: StringAtom(file) :: IntAtom(point) :: body) => {
            rpcTarget.rpcInspectTypeAtPoint(file, point, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:inspect-type-by-id
       * Summary:
       *   Lookup detailed type description by id
       * Arguments:
       *   Int:A type id.
       * Return:
       *   A TypeInspectInfo
       * Example call:
       *   (:swank-rpc (swank:inspect-type-by-id 232) 42)
       * Example return:
       *   (:return (:ok (:type (:name "SExpList$" :type-id 1469 :full-name
       *   "org.ensime.util.SExpList$" :decl-as object :pos
       *   (:file "SExp.scala" :offset 1877))......)) 42)
       */
      case "swank:inspect-type-by-id" => {
        form match {
          case SExpList(head :: IntAtom(id) :: body) => {
            rpcTarget.rpcInspectTypeById(id, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:symbol-at-point
       * Summary:
       *   Get a description of the symbol at given location.
       * Arguments:
       *   String:A source filename.
       *   Int:A character offset in the file.
       * Return:
       *   A SymbolInfo
       * Example call:
       *   (:swank-rpc (swank:symbol-at-point "SwankProtocol.scala" 36483) 42)
       * Example return:
       *   (:return (:ok (:name "file" :type (:name "String" :type-id 25
       *   :full-name "java.lang.String" :decl-as class) :decl-pos
       *   (:file "SwankProtocol.scala" :offset 36404))) 42)
       */
      case "swank:symbol-at-point" => {
        form match {
          case SExpList(head :: StringAtom(file) :: IntAtom(point) :: body) => {
            rpcTarget.rpcSymbolAtPoint(file, point, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:inspect-package-by-path
       * Summary:
       *   Get a detailed description of the given package.
       * Arguments:
       *   String:A qualified package name.
       * Return:
       *   A PackageInfo
       * Example call:
       *   (:swank-rpc (swank:inspect-package-by-path "org.ensime.util" 36483) 42)
       * Example return:
       *   (:return (:ok (:name "util" :info-type package :full-name "org.ensime.util"
       *   :members ((:name "BooleanAtom" :type-id 278 :full-name
       *   "org.ensime.util.BooleanAtom" :decl-as class :pos
       *   (:file "SExp.scala" :offset 2848)).....))) 42)
       */
      case "swank:inspect-package-by-path" => {
        form match {
          case SExpList(head :: StringAtom(path) :: body) => {
            rpcTarget.rpcInspectPackageByPath(path, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:prepare-refactor
       * Summary:
       *   Initiate a refactoring. The server will respond with a summary
       *   of what the refactoring *would* do, were it executed.This call
       *   does not effect any changes unless the 4th argument is nil.
       * Arguments:
       *   Int:A procedure id for this refactoring, uniquely generated by client.
       *   Symbol:The manner of refacoring we want to prepare. Currently, one of
       *     rename, extractMethod, extractLocal, organizeImports, or addImport.
       *   An association list of params of the form (sym1 val1 sym2 val2).
       *     Contents of the params varies with the refactoring type:
       *     rename: (newName String file String start Int end Int)
       *     extractMethod: (methodName String file String start Int end Int)
       *     extractLocal: (name String file String start Int end Int)
       *     inlineLocal: (file String start Int end Int)
       *     organizeImports: (file String)
       *     addImport: (qualifiedName String file String start Int end Int)
       *   Bool:Should the refactoring require confirmation? If nil, the refactoring
       *     will be executed immediately.
       * Return:
       *   RefactorEffect | RefactorFailure
       * Example call:
       *   (:swank-rpc (swank:prepare-refactor 6 rename (file "SwankProtocol.scala"
       *   start 39504 end 39508 newName "dude") t) 42)
       * Example return:
       *   (:return (:ok (:procedure-id 6 :refactor-type rename :status success
       *   :changes ((:file "SwankProtocol.scala" :text "dude" :from 39504 :to 39508))
       *   )) 42)
       */
      case "swank:prepare-refactor" => {
        form match {
          case SExpList(head :: IntAtom(procId) :: SymbolAtom(tpe) ::
            (params: SExp) :: BooleanAtom(interactive) :: body) => {
            rpcTarget.rpcPrepareRefactor(Symbol(tpe), procId,
              listOrEmpty(params).toSymbolMap, interactive, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:exec-refactor
       * Summary:
       *   Execute a refactoring, usually after user confirmation.
       * Arguments:
       *   Int:A procedure id for this refactoring, uniquely generated by client.
       *   Symbol:The manner of refacoring we want to prepare. Currently, one of
       *     rename, extractMethod, extractLocal, organizeImports, or addImport.
       * Return:
       *   RefactorResult | RefactorFailure
       * Example call:
       *   (:swank-rpc (swank:exec-refactor 7 rename) 42)
       * Example return:
       *   (:return (:ok (:procedure-id 7 :refactor-type rename
       *   :touched-files ("SwankProtocol.scala"))) 42)
       */
      case "swank:exec-refactor" => {
        form match {
          case SExpList(dude :: IntAtom(procId) :: SymbolAtom(tpe) :: body) => {
            rpcTarget.rpcExecRefactor(Symbol(tpe), procId, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:cancel-refactor
       * Summary:
       *   Cancel a refactor that's been performed but not
       *   executed.
       * Arguments:
       *   Int:Procedure Id of the refactoring.
       * Return:
       *   None
       * Example call:
       *   (:swank-rpc (swank:cancel-refactor 1) 42)
       * Example return:
       *   (:return (:ok t))
       */
      case "swank:cancel-refactor" => {
        form match {
          case SExpList(head :: IntAtom(procId) :: body) => {
            rpcTarget.rpcCancelRefactor(procId, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:symbol-designations
       * Summary:
       *   Request the semantic classes of symbols in the given
       *   range. These classes are intended to be used for
       *   semantic highlighting.
       * Arguments:
       *   String:A source filename.
       *   Int:The character offset of the start of the input range.
       *   Int:The character offset of the end of the input range.
       *   List of Symbol:The symbol classes in which we are interested.
       *     Available classes are: var,val,varField,valField,functionCall,
       *     operator,param,class,trait,object.
       * Return:
       *   SymbolDesignations
       * Example call:
       *   (:swank-rpc (swank:symbol-designations "SwankProtocol.scala" 0 46857
       *   (var val varField valField)) 42)
       * Example return:
       *   (:return (:ok (:file "SwankProtocol.scala" :syms
       *   ((varField 33625 33634) (val 33657 33661) (val 33663 33668)
       *   (varField 34369 34378) (val 34398 34400)))) 42)
       */
      case "swank:symbol-designations" => {
        form match {
          case SExpList(head :: StringAtom(filename) :: IntAtom(start) ::
            IntAtom(end) :: (reqTypes:SExp) :: body) => {
            val requestedTypes: List[Symbol] = listOrEmpty(reqTypes).map(
              tpe => Symbol(tpe.toString)).toList
            rpcTarget.rpcSymbolDesignations(filename, start,
              end, requestedTypes, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:expand-selection
       * Summary:
       *   Given a start and end point in a file, expand the
       *   selection so that it spans the smallest syntactic
       *   scope that contains start and end.
       * Arguments:
       *   String:A source filename.
       *   Int:The character offset of the start of the input range.
       *   Int:The character offset of the end of the input range.
       * Return:
       *   A RangePosition:The expanded range.
       * Example call:
       *   (:swank-rpc (swank:expand-selection "Model.scala" 4648 4721) 42)
       * Example return:
       *   (:return (:ok (:file "Model.scala" :start 4374 :end 14085)) 42)
       */
      case "swank:expand-selection" => {
        form match {
          case SExpList(head :: StringAtom(filename) :: IntAtom(start) ::
            IntAtom(end) :: body) => {
            rpcTarget.rpcExpandSelection(filename, start, end, callId)
          }
          case _ => oops
        }
      }

      /**
       * Doc:
       *   swank:shutdown-server
       * Summary:
       *   Politely ask the server to shutdown.
       * Arguments:
       *   None
       * Return:
       *   None
       * Example call:
       *   (:swank-rpc (swank:shutdown-server) 42)
       * Example return:
       *   (:return (:ok t) 42)
       */
      case "swank:shutdown-server" => {
        rpcTarget.rpcShutdownServer(callId)
      }

      case other => {
        sendRPCError(
          ErrUnrecognizedRPC,
          Some("Unknown :swank-rpc call: " + other),
          callId)
      }
    }
  }

  def listOrEmpty(list: SExp): SExpList = {
    list match {
      case l: SExpList => l
      case _ => SExpList(List())
    }
  }

  def sendRPCAckOK(callId: Int) {
    sendRPCReturn(true, callId)
  }

  def sendRPCReturn(value: WireFormat, callId: Int) {
    value match {
      case sexp: SExp =>
        {
          sendMessage(SExp(
            key(":return"),
            SExp(key(":ok"), sexp),
            callId))
        }
      case _ => throw new IllegalStateException("Not a SExp: " + value)
    }
  }

  def sendRPCError(code: Int, detail: Option[String], callId: Int) {
    sendMessage(SExp(
      key(":return"),
      SExp(key(":abort"),
        code,
        detail.map(strToSExp).getOrElse(NilAtom())),
      callId))
  }

  def sendProtocolError(code: Int, detail: Option[String]) {
    sendMessage(
      SExp(
        key(":reader-error"),
        code,
        detail.map(strToSExp).getOrElse(NilAtom())))
  }


  /*
* A sexp describing the server configuration, per the Swank standard.
*/
  def sendConnectionInfo(callId: Int) = {
    val info = SExp(
      key(":pid"), 'nil,
      key(":implementation"),
      SExp(
        key(":name"), ServerName),
      key(":version"), ProtocolVersion)
    sendRPCReturn(info, callId)
  }

  def sendCompilerReady() = sendMessage(SExp(key(":compiler-ready"), true))

  def sendFullTypeCheckComplete() = sendMessage(SExp(key(":full-typecheck-finished"), true))

  def sendIndexerReady() = sendMessage(SExp(key(":indexer-ready"), true))

  def sendNotes(lang: scala.Symbol, notes: NoteList) {
    if (lang == 'scala) sendMessage(SExp(key(":scala-notes"), toWF(notes)))
    else if (lang == 'java) sendMessage(SExp(key(":java-notes"), toWF(notes)))
  }

  def sendClearAllNotes(lang: scala.Symbol) {
    if (lang == 'scala) sendMessage(SExp(key(":clear-all-scala-notes"), true))
    else if (lang == 'java) sendMessage(SExp(key(":clear-all-java-notes"), true))
  }

  object SExpConversion {

    implicit def posToSExp(pos: Position): SExp = {
      if (pos.isDefined) {
        SExp.propList((":file", pos.source.path), (":offset", pos.point))
      } else {
        'nil
      }
    }

    implicit def posToSExp(pos: RangePosition): SExp = {
      if (pos.isDefined) {
        SExp.propList(
          (":file", pos.source.path),
          (":offset", pos.point),
          (":start", pos.start),
          (":end", pos.end))
      } else {
        'nil
      }
    }

  }

  import SExpConversion._

  def toWF(config: ProjectConfig): SExp = {
    SExp(
      key(":project-name"), config.name.map(StringAtom).getOrElse('nil),
      key(":source-roots"), SExp(config.sourceRoots.map { f => StringAtom(f.getPath) }))
  }

  def toWF(config: ReplConfig): SExp = {
    SExp.propList((":classpath", strToSExp(config.classpath)))
  }

  def toWF(config: DebugConfig): SExp = {
    SExp.propList(
      (":classpath", strToSExp(config.classpath)),
      (":sourcepath", strToSExp(config.sourcepath)))
  }

  def toWF(unit: DebugUnit): SExp = {
    SExp.propList(
      (":full-name", strToSExp(unit.classQualName)),
      (":package", strToSExp(unit.packageName)),
      (":start-line", intToSExp(unit.startLine)),
      (":end-line", intToSExp(unit.endLine)))
  }

  def toWF(value: Boolean): SExp = {
    if (value) TruthAtom()
    else NilAtom()
  }

  def toWF(value: Null): SExp = {
    NilAtom()
  }

  def toWF(value: String): SExp = {
    StringAtom(value)
  }

  def toWF(value: DebugSourceLinePairs): SExp = {
    SExpList(value.pairs.map { p => SExp(p._1, p._2) })
  }

  def toWF(note: Note): SExp = {
    SExp(
      key(":severity"), note.friendlySeverity,
      key(":msg"), note.msg,
      key(":beg"), note.beg,
      key(":end"), note.end,
      key(":line"), note.line,
      key(":col"), note.col,
      key(":file"), note.file)
  }

  def toWF(notelist: NoteList): SExp = {
    val NoteList(isFull, notes) = notelist
    SExp(
      key(":is-full"),
      toWF(isFull),
      key(":notes"),
      SExpList(notes.map(toWF)))
  }

  def toWF(values: Iterable[WireFormat]): SExp = {
    SExpList(values.map(ea => ea.asInstanceOf[SExp]))
  }

  def toWF(value: SymbolInfoLight): SExp = {
    SExp.propList(
      (":name", value.name),
      (":type-sig", value.tpeSig),
      (":type-id", value.tpeId),
      (":is-callable", value.isCallable))
  }

  def toWF(value: PackageMemberInfoLight): SExp = {
    SExp(key(":name"), value.name)
  }

  def toWF(value: SymbolInfo): SExp = {
    SExp.propList(
      (":name", value.name),
      (":type", toWF(value.tpe)),
      (":decl-pos", value.declPos),
      (":is-callable", value.isCallable))
  }

  def toWF(value: FileRange): SExp = {
    SExp.propList(
      (":file", value.file),
      (":start", value.start),
      (":end", value.end))
  }

  def toWF(value: Position): SExp = {
    posToSExp(value)
  }

  def toWF(value: RangePosition): SExp = {
    posToSExp(value)
  }

  def toWF(value: NamedTypeMemberInfoLight): SExp = {
    SExp.propList(
      (":name", value.name),
      (":type-sig", value.tpeSig),
      (":type-id", value.tpeId),
      (":is-callable", value.isCallable))
  }

  def toWF(value: NamedTypeMemberInfo): SExp = {
    SExp.propList(
      (":name", value.name),
      (":type", toWF(value.tpe)),
      (":pos", value.pos),
      (":decl-as", value.declaredAs))
  }

  def toWF(value: EntityInfo): SExp = {
    value match {
      case value: PackageInfo => toWF(value)
      case value: TypeInfo => toWF(value)
      case value: NamedTypeMemberInfo => toWF(value)
      case value: NamedTypeMemberInfoLight => toWF(value)
      case value => throw new IllegalStateException("Unknown EntityInfo: " + value)
    }
  }

  def toWF(value: TypeInfo): SExp = {
    value match {
      case value: ArrowTypeInfo =>
        {
          SExp.propList(
            (":name", value.name),
            (":type-id", value.id),
            (":arrow-type", true),
            (":result-type", toWF(value.resultType)),
            (":param-sections", SExp(value.paramSections.map(toWF))))
        }
      case value: TypeInfo =>
        {
          SExp.propList((":name", value.name),
            (":type-id", value.id),
            (":full-name", value.fullName),
            (":decl-as", value.declaredAs),
            (":type-args", SExp(value.args.map(toWF))),
            (":members", SExp(value.members.map(toWF))),
            (":pos", value.pos),
            (":outer-type-id", value.outerTypeId.map(intToSExp).getOrElse('nil)))
        }
      case value => throw new IllegalStateException("Unknown TypeInfo: " + value)
    }
  }

  def toWF(value: PackageInfo): SExp = {
    SExp.propList((":name", value.name),
      (":info-type", 'package),
      (":full-name", value.fullname),
      (":members", SExpList(value.members.map(toWF))))
  }

  def toWF(value: CallCompletionInfo): SExp = {
    SExp.propList(
      (":result-type", toWF(value.resultType)),
      (":param-sections", SExp(value.paramSections.map(toWF))))
  }

  def toWF(value: ParamSectionInfo): SExp = {
    SExp.propList(
      (":params", SExp(value.params.map {
        case (nm, tp) => SExp(nm, toWF(tp))
      })),
      (":is-implicit", value.isImplicit))

  }

  def toWF(value: InterfaceInfo): SExp = {
    SExp.propList(
      (":type", toWF(value.tpe)),
      (":via-view", value.viaView.map(strToSExp).getOrElse('nil)))
  }

  def toWF(value: TypeInspectInfo): SExp = {
    SExp.propList(
      (":type", toWF(value.tpe)),
      (":info-type", 'typeInspect),
      (":companion-id", value.companionId match {
        case Some(id) => id
        case None => 'nil
      }), (":interfaces", SExp(value.supers.map(toWF))))
  }

  def toWF(value: RefactorFailure): SExp = {
    SExp.propList(
      (":procedure-id", value.procedureId),
      (":status", 'failure),
      (":reason", value.message))
  }

  def toWF(value: RefactorEffect): SExp = {
    SExp.propList(
      (":procedure-id", value.procedureId),
      (":refactor-type", value.refactorType),
      (":status", 'success),
      (":changes", SExpList(value.changes.map(changeToWF))))
  }

  def toWF(value: RefactorResult): SExp = {
    SExp.propList(
      (":procedure-id", value.procedureId),
      (":refactor-type", value.refactorType),
      (":status", 'success),
      (":touched-files", SExpList(value.touched.map(f => strToSExp(f.getAbsolutePath)))))
  }

  def toWF(value: SymbolSearchResults): SExp = {
    SExpList(value.syms.map(toWF))
  }

  def toWF(value: ImportSuggestions): SExp = {
    SExpList(value.symLists.map { l => SExpList(l.map(toWF)) })
  }

  private def toWF(pos: Option[(String, Int)]): SExp = {
    pos match {
      case Some((f, o)) => SExp.propList((":file", f), (":offset", o))
      case _ => 'nil
    }
  }

  def toWF(value: SymbolSearchResult): SExp = {
    value match {
      case value: TypeSearchResult => {
        SExp.propList(
          (":name", value.name),
          (":local-name", value.localName),
          (":decl-as", value.declaredAs),
          (":pos", toWF(value.pos)))
      }
      case value: MethodSearchResult => {
        SExp.propList(
          (":name", value.name),
          (":local-name", value.localName),
          (":decl-as", value.declaredAs),
          (":pos", toWF(value.pos)),
          (":owner-name", value.owner))
      }
      case value => throw new IllegalStateException("Unknown SymbolSearchResult: " + value)
    }

  }

  def toWF(value: Undo): SExp = {
    SExp.propList(
      (":id", value.id),
      (":changes", SExpList(value.changes.map(changeToWF))),
      (":summary", value.summary))
  }

  def toWF(value: UndoResult): SExp = {
    SExp.propList(
      (":id", value.id),
      (":touched-files", SExpList(value.touched.map(f => strToSExp(f.getAbsolutePath)))))
  }

  def toWF(value: SymbolDesignations): SExp = {
    SExp.propList(
      (":file", value.file),
      (":syms",
        SExpList(value.syms.map { s =>
          SExpList(List(s.symType, s.start, s.end))
        })))
  }

  private def changeToWF(ch: Change): SExp = {
    SExp.propList(
      (":file", ch.file.path),
      (":text", ch.text),
      (":from", ch.from),
      (":to", ch.to))
  }

}
