package com.github.helkaroui.sbtextendedcache

import org.apache.ivy.core.cache.ArtifactOrigin
import sbt.Keys.*
import sbt.internal.RemoteCache.*
import sbt.internal.inc.JarUtils
import sbt.internal.librarymanagement.IvyActions.mapArtifacts
import sbt.nio.Keys.{checkBuildSources, inputFileStamps}
import sbt.plugins.SemanticdbPlugin
import sbt.{Def, *}

import scala.util.matching.Regex

object ExtendedCachePlugin extends AutoPlugin {

  private val pluginSeed = "1f11dcbf"
  private val basePlaceholderRegex = "\\$\\{BASE\\}".r
  private val cachesDirectory = "__caches"

  override def trigger: PluginTrigger = allRequirements

  object autoImport {
    val pushExtendedRemoteCache = taskKey[Unit](
      "Push remote cache artifact(s) to the cache server " +
        "if the artifact is not already present in the local repository."
    )
    // See https://github.com/sbt/sbt/issues/6286
    val pullExtendedRemoteCache = taskKey[Option[String]](
      "Retrieve the remote cache artifact unless the previous run already " +
        "attempted to retrieve the same remote cache id."
    )
    val cacheGenerators = settingKey[Seq[Task[Seq[File]]]](
      "List of tasks that generate cache files to include in remote cache artifacts."
    )

  }

  import autoImport.*

  // TODO: check the impact of the following settings
  // For local testing
  override def globalSettings: Seq[Def.Setting[_]] = Seq(
    pushRemoteCacheTo := Some(MavenCache("local-cache", file("/tmp/remote-cache"))),
    allowMachinePath := false // Ensure ThisBuild / rootPaths contains all necessary paths to abstract machine-specific path
  )

  private val anyConfigsInThisProject = ScopeFilter(
    configurations = inAnyConfiguration
  )

  private val remoteCacheSettings: Seq[Def.Setting[_]] = Seq(
    pushExtendedRemoteCache := pushExtendedRemoteCache.?.all(anyConfigsInThisProject).value
  )

  override def projectSettings: Seq[Def.Setting[_]] =
    remoteCacheSettings ++ Seq(Compile, Test).flatMap(remoteCacheConfigSettings)

  private def cachedClassifier(conf: Configuration) = s"cached-${conf.name}"

  private def semanticdbConfigSettings(config: Configuration): Seq[Def.Setting[_]] =
    inConfig(config)(
      Seq(
        // include semanticdb in the remote cache artifact as soon as it is enabled
        semanticdbIncludeInJar := semanticdbEnabled.value
      ) ++ SemanticdbPlugin.configurationSettings
    )

  // TODO: refactor if not needed anymore
  // The scalac options are stored in the zinc cache, thus these options must be machine-independent.
  // https://github.com/sbt/sbt/issues/6027
  private def zincCacheWorkaroundsSettings(config: Configuration): Seq[Def.Setting[_]] = Seq(
    // relativize semantic DB target root which is part of the scalac options
    // https://github.com/sbt/sbt/issues/6027#issuecomment-717064450
    config / semanticdbTargetRoot := {
      val old = (config / semanticdbTargetRoot).value.toPath
      (LocalRootProject / baseDirectory).value.toPath.relativize(old).toFile
    }
  )

  private def remoteCacheConfigSettings(config: Configuration): Seq[Setting[_]] = {
    inConfig(config)(
      configCacheSettings(compileArtifact(config, cachedClassifier(config))) ++ Seq(
        // All tasks consuming classDirectory (where the remote cache is extracted)
        // should block until the pulling is over
        compileIncremental := compileIncremental.dependsOn(pullExtendedRemoteCache).value,
        products := products.dependsOn(pullExtendedRemoteCache).value,
        copyResources := copyResources.dependsOn(pullExtendedRemoteCache).value,
        packageCache / artifact := Artifact(moduleName.value, cachedClassifier(config)),
        cacheGenerators := Seq.empty,
        pullExtendedRemoteCache := Def
          .taskDyn[Option[String]] {
            import CacheImplicits.*
            val log = streams.value.log
            val previousPulledRemoteCacheId = remoteCacheId.previous
            // if we have uncommited changes, there is very little chance that we get a hit so don't bother trying

            Def.taskDyn[Option[String]] { // lookup remoteCacheId within a nested task to avoid doing it unnecessarily
              val currentPullRemoteCacheId = remoteCacheId.value
              // don't try to pull something we already pulled or tried to pull before
              if (previousPulledRemoteCacheId.contains(currentPullRemoteCacheId)) {
                log.debug(
                  s"attempt to pull $currentPullRemoteCacheId detected, not attempting again"
                )
                Def.task(previousPulledRemoteCacheId)
              } else pullRemoteCache.map(_ => Some(currentPullRemoteCacheId))
            }

          }.value,

        pushExtendedRemoteCache := Def.taskDyn {
          val log = streams.value.log
          val is = (pushRemoteCache / ivySbt).value
          val projectId = remoteCacheProjectId.value
          val config = pushRemoteCacheConfiguration.value
          val module = new is.Module((pushRemoteCache / moduleSettings).value)
          val artifacts = Map(config.artifacts: _*)
          val classifier = artifacts.keySet.map(_.classifier).mkString
          val isArtifactInLocalCache = module.withModule(log) { case (ivy, md, _) =>
            def crossVersionMap(moduleSettings: ModuleSettings): Option[String => String] =
              moduleSettings match {
                case i: InlineConfiguration => CrossVersion(i.module, i.scalaModuleInfo)
                case _ => None
              }

            val resolver = ivy.getSettings.getDefaultRepositoryCacheManager
            val cross = crossVersionMap(module.moduleSettings)
            val ivyArtifacts = mapArtifacts(md, cross, artifacts).map(_._1)
            ivyArtifacts
              .map(resolver.getSavedArtifactOrigin).forall(
                !ArtifactOrigin.isUnknown(_)
              )
          }
          if (isArtifactInLocalCache) {
            log.debug(s"remote cache artifact already published for $projectId $classifier")
            Def.task(())
          } else {
            pushRemoteCache.result.map {
              case Inc(inc: Incomplete) =>
                log.error(
                  s"Something went wrong when publishing the remote cache artifact for $projectId $classifier"
                )
                inc.directCause.foreach(log.trace(_))
              case _ =>
            }
          }
        }.value,

        remoteCacheId := Def.taskDyn {
          Def.value {
            val data = settingsData.value
            val log = streams.value.log
            val thisProject = thisProjectRef.value

            def remoteCacheIdTask(project: ProjectRef, configuration: String): Task[String] =
              (project / ConfigKey(configuration) / remoteCacheId).get(data) match {
                case Some(value) => value
                case _ => constant("")
              }

            val tasks: Seq[Task[String]] = internalDependencyConfigurations.value.flatMap {
              case (project, configurations) =>
                configurations
                  // avoid circular call to remoteCacheId
                  .filterNot(_ == config.name && project == thisProject)
                  .map(c => remoteCacheIdTask(project, c))
            }

            // the parent value takes into account:
            //  - the sources (.scala)
            //  - the dependencies including transitive (.jar)
            //  - the extra options for the incremental compiler
            val parentValue = remoteCacheId.value
            log.debug(s"1 ${name.value} ${config.name} $parentValue")

            // TODO. solve issues with join
            tasks.join.map(x => {
              DirectoryUtils.combineHash(parentValue, x: _*)
            })
          }
        }.value,

        remoteCacheId := {
          val log = streams.value.log
          // Take resources into account when generating the remote cache key
          //  - proto files have an impact on the generated code
          //  - other resources can have an impact on the test execution
          // But gitignored files should not have an impact on the remote cache key
          val excluded = Set("parameters.conf", "parameters.conf.sample", "local.sbt")
          val resources = (unmanagedResources / inputFileStamps).value
          // Put the build definition in the hash as it can change the build output
          val buildFiles = (Global / checkBuildSources / inputFileStamps).value
          val inputs = (resources ++ buildFiles)
            .filterNot { case (path, _) =>
              excluded.contains(path.getFileName.toString)
            }.map { case (_, stamp0) =>
              stamp0.toString
            }
          val previous = remoteCacheId.value
          log.debug(s"2 ${name.value} ${config.name} $previous")
          DirectoryUtils.combineHash(previous, inputs: _*)
        },
        // also put the repositories used for the build as they are part of the incremental compile inputs
        remoteCacheId := {
          val log = streams.value.log
          val resolversToString = fullResolvers.value.map(_.toString).mkString
          val previous = remoteCacheId.value
          log.debug(s"3 ${name.value} ${config.name} $previous")
          DirectoryUtils.combineHash(resolversToString, previous)
        },
        // combine with a version to allow for breaking changes in the remote cache format
        remoteCacheId := {
          val log = streams.value.log
          val previous = remoteCacheId.value
          log.debug(s"4 ${name.value} ${config.name} $previous")
          DirectoryUtils.combineHash(pluginSeed, previous)
        },

        packageCache := {
          // Call the parent task to create the remote cache jar containing the compiled classes, the unmanaged
          // resources and also the zinc incremental cache
          val cacheJar = packageCache.value
          val log = streams.value.log
          val base = (ThisProject / baseDirectory).value
          val cacheFiles = Defaults.generate(cacheGenerators).value

          // val baseRegex = base.toString.r
          JarUtils.includeInJar(
            cacheJar,
            cacheFiles
              .map { file =>
                log.debug(s"Including $file into package cache")
                // Make cache file content machine-independent by transforming absolute paths to path relative to the
                // project base directory. (awaiting https://github.com/sbt/sbt/issues/6298)
                val transformedFile =
                  IO.withTemporaryFile(file.getName, "transformed", keepFile = true) {
                    temporaryFile =>
                      val content = IO.read(file)
                      val transformedContent = Regex.quote(base.getAbsolutePath).r.replaceAllIn(content, basePlaceholderRegex.toString())
                      IO.write(temporaryFile, transformedContent)
                      temporaryFile
                  }
                val jarEntry = s"$cachesDirectory/${IO.relativize(base, file).get}"
                transformedFile -> jarEntry
              }
          )
          cacheJar
        },
        pullRemoteCache := {
          pullRemoteCache.value
          val log = streams.value.log
          // Cache files have been extracted into __caches by the parent pullRemoteCache,  we now move them to their
          // expected destination and turn them machine-*dependent* again by transforming relative paths
          // to absolute ones
          val base = (ThisProject / baseDirectory).value
          val extractedCacheDir = classDirectory.value / cachesDirectory
          val cacheFiles = extractedCacheDir.allPaths.filter(_.isFile).get
          cacheFiles.foreach { cacheFile =>
            val target = base / IO.relativize(extractedCacheDir, cacheFile).get
            log.debug(s"Restoring $target from package cache")
            val content = basePlaceholderRegex.replaceAllIn(IO.read(cacheFile), base.toString)
            IO.write(target, content)
          }
          IO.delete(extractedCacheDir)
        }
      ) ++ semanticdbConfigSettings(config) ++ zincCacheWorkaroundsSettings(config)
    )
  }
}
