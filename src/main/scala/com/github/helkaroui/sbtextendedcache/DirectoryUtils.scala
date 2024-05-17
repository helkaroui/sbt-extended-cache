package com.github.helkaroui.sbtextendedcache

import sbt.File
import sbt.internal.inc.HashUtil

object DirectoryUtils {

  def hasLocalChanges(tree: File): Boolean =
    scala.sys.process.Process(s"git status --porcelain -- ${tree.getAbsolutePath}").!!.trim.nonEmpty

  private def combineHash(vs: Seq[String]): String = {
    val hashValue = HashUtil.farmHash(vs.sorted.mkString("").getBytes("UTF-8"))
    java.lang.Long.toHexString(hashValue)
  }

  def combineHash(str: String, strings: String*): String =
    combineHash(str +: strings)

}
