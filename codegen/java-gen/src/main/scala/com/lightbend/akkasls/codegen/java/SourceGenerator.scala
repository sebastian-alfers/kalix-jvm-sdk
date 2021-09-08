/*
 * Copyright 2021 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lightbend.akkasls.codegen.java

import _root_.java.nio.file.{Files, Path, Paths}
import _root_.java.io.File
import com.google.common.base.Charsets

import scala.collection.immutable
import com.lightbend.akkasls.codegen._
import com.lightbend.akkasls.codegen.DescriptorSet
import com.lightbend.akkasls.codegen.Log
import com.lightbend.akkasls.codegen.ModelBuilder
import com.lightbend.akkasls.codegen.ModelBuilder.{Command, Entity, Service, State}

/**
 * Responsible for generating Java source from an entity model
 */
object SourceGenerator {
  import EntityServiceSourceGenerator.generateImports

  /**
   * Generate Java source from entities where the target source and test source directories have no existing source.
   * Note that we only generate tests for entities where we are successful in generating an entity. The user may
   * not want a test otherwise.
   *
   * Also generates a main source file if it does not already exist.
   *
   * Impure.
   *
   * @param model The model of entity metadata to generate source file
   * @param sourceDirectory A directory to generate source files in, which can also containing existing source.
   * @param testSourceDirectory A directory to generate test source files in, which can also containing existing source.
   * @param integrationTestSourceDirectory A directory to generate integration test source files in, which can also containing existing source.
   * @param mainClass  A fully qualified classname to be used as the main class
   * @return A collection of paths addressing source files generated by this function
   */
  def generate(
      model: ModelBuilder.Model,
      sourceDirectory: Path,
      testSourceDirectory: Path,
      integrationTestSourceDirectory: Path,
      generatedSourceDirectory: Path,
      generatedTestSourceDirectory: Path,
      mainClass: String
  )(implicit log: Log): Iterable[Path] = {

    val (mainClassPackageName, mainClassName) = disassembleClassName(mainClass)

    model.services.values.flatMap {
      case service: ModelBuilder.EntityService =>
        model.entities.get(service.componentFullName) match {
          case None =>
            // TODO perhaps we even want to make this an error, to really go all-in on codegen?
            log.warning(
              "Service [" + service.fqn.fullQualifiedName + "] refers to entity [" + service.componentFullName +
              "], but no entity configuration is found for that component name"
            )
            Seq.empty
          case Some(entity) =>
            EntityServiceSourceGenerator.generate(
              entity,
              service,
              sourceDirectory,
              testSourceDirectory,
              integrationTestSourceDirectory,
              generatedSourceDirectory,
              mainClassPackageName,
              mainClassName
            ) ++
            EventSourcedEntityTestKitGenerator.generate(
              entity,
              service,
              generatedTestSourceDirectory
            )
        }
      case service: ModelBuilder.ViewService =>
        ViewServiceSourceGenerator.generate(
          service,
          sourceDirectory,
          testSourceDirectory,
          integrationTestSourceDirectory,
          generatedSourceDirectory
        )
      case service: ModelBuilder.ActionService =>
        ActionServiceSourceGenerator.generate(
          service,
          sourceDirectory,
          generatedSourceDirectory
        )
      case _ => Seq.empty
    } ++ {
      val mainClassPackagePath = packageAsPath(mainClassPackageName)

      val akkaServerlessFactorySourcePath =
        generatedSourceDirectory.resolve(
          mainClassPackagePath.resolve("AkkaServerlessFactory.java")
        )

      akkaServerlessFactorySourcePath.getParent.toFile.mkdirs()
      Files.write(
        akkaServerlessFactorySourcePath,
        akkaServerlessFactorySource(mainClassPackageName, model).getBytes(Charsets.UTF_8)
      )

      // Generate a main source file if it is not there already

      val mainClassPath =
        sourceDirectory.resolve(mainClassPackagePath.resolve(mainClassName + ".java"))
      if (!mainClassPath.toFile.exists()) {
        mainClassPath.getParent.toFile.mkdirs()
        Files.write(
          mainClassPath,
          mainSource(mainClassPackageName, mainClassName, model.entities, model.services).getBytes(Charsets.UTF_8)
        )
        List(akkaServerlessFactorySourcePath, mainClassPath)
      } else {
        List(akkaServerlessFactorySourcePath)
      }
    }
  }

  def generate(protobufDescriptor: File,
               sourceDirectory: Path,
               testSourceDirectory: Path,
               integrationTestSourceDirectory: Path,
               generatedSourceDirectory: Path,
               generatedTestSourceDirectory: Path,
               mainClass: String)(implicit log: Log): Iterable[Path] = {
    val descriptors =
      DescriptorSet.fileDescriptors(protobufDescriptor) match {
        case Right(fileDescriptors) =>
          fileDescriptors match {
            case Right(files) => files
            case Left(failure) =>
              throw new RuntimeException(
                s"There was a problem building the file descriptor from its protobuf: $failure"
              )
          }
        case Left(failure) =>
          throw new RuntimeException(s"There was a problem opening the protobuf descriptor file ${failure}", failure.e)
      }

    SourceGenerator.generate(
      ModelBuilder.introspectProtobufClasses(descriptors),
      sourceDirectory,
      testSourceDirectory,
      integrationTestSourceDirectory,
      generatedSourceDirectory,
      generatedTestSourceDirectory,
      mainClass
    )

  }

  def collectRelevantTypes(fullQualifiedNames: Iterable[FullyQualifiedName],
                           service: FullyQualifiedName): immutable.Seq[FullyQualifiedName] = {
    fullQualifiedNames.filterNot { desc =>
      desc.parent == service.parent
    }.toList
  }

  def collectRelevantTypeDescriptors(fullQualifiedNames: Iterable[FullyQualifiedName],
                                     service: FullyQualifiedName): String = {
    collectRelevantTypes(fullQualifiedNames, service)
      .map(desc => s"${desc.parent.javaOuterClassname}.getDescriptor()")
      .distinct
      .sorted
      .mkString(",\n")
  }

  private[codegen] def akkaServerlessFactorySource(
      mainClassPackageName: String,
      model: ModelBuilder.Model
  ): String = {
    val registrations = model.services.values
      .flatMap {
        case service: ModelBuilder.EntityService =>
          model.entities.get(service.componentFullName).toSeq.map {
            case entity: ModelBuilder.EventSourcedEntity =>
              s".register(${entity.fqn.name}Provider.of(create${entity.fqn.name}))"
            case entity: ModelBuilder.ValueEntity =>
              s".register(${entity.fqn.name}Provider.of(create${entity.fqn.name}))"
            case entity: ModelBuilder.ReplicatedEntity =>
              s".register(${entity.fqn.name}Provider.of(create${entity.fqn.name}))"
          }

        case service: ModelBuilder.ViewService =>
          List(s".register(${service.providerName}.of(create${service.viewClassName}))")

        case service: ModelBuilder.ActionService =>
          List(s".register(${service.providerName}.of(create${service.className}))")

      }
      .toList
      .sorted

    val entityImports = model.entities.values.flatMap { ety =>
      if (ety.fqn.parent.javaPackage != mainClassPackageName) {
        val imports =
          ety.fqn.fullQualifiedName ::
          s"${ety.fqn.parent.javaPackage}.${ety.fqn.parent.javaOuterClassname}" ::
          Nil
        ety match {
          case _: ModelBuilder.EventSourcedEntity =>
            s"${ety.fqn.fullQualifiedName}Provider" :: imports
          case _: ModelBuilder.ValueEntity =>
            s"${ety.fqn.fullQualifiedName}Provider" :: imports
          case _: ModelBuilder.ReplicatedEntity =>
            s"${ety.fqn.fullQualifiedName}Provider" :: imports
          case _ => imports
        }
      } else List.empty
    }

    val serviceImports = model.services.values.flatMap { serv =>
      if (serv.fqn.parent.javaPackage != mainClassPackageName) {
        val outerClass = s"${serv.fqn.parent.javaPackage}.${serv.fqn.parent.javaOuterClassname}"
        serv match {
          case actionServ: ModelBuilder.ActionService =>
            List(actionServ.classNameQualified, actionServ.providerNameQualified, outerClass)
          case view: ModelBuilder.ViewService =>
            List(view.classNameQualified, view.providerNameQualified, outerClass)
          case _ => List(outerClass)
        }
      } else List.empty
    }

    val otherImports = model.services.values.flatMap { serv =>
      val types = serv.commands.flatMap { cmd =>
        cmd.inputType :: cmd.outputType :: Nil
      }
      collectRelevantTypes(types, serv.fqn).map { typ =>
        s"${typ.parent.javaPackage}.${typ.parent.javaOuterClassname}"
      }
    }

    val entityContextImports = model.entities.values.collect {
      case _: ModelBuilder.EventSourcedEntity =>
        List(
          "com.akkaserverless.javasdk.eventsourcedentity.EventSourcedEntityContext",
          "java.util.function.Function"
        )
      case _: ModelBuilder.ValueEntity =>
        List(
          "com.akkaserverless.javasdk.valueentity.ValueEntityContext",
          "java.util.function.Function"
        )
      case _: ModelBuilder.ReplicatedEntity =>
        List(
          "com.akkaserverless.javasdk.replicatedentity.ReplicatedEntityContext",
          "java.util.function.Function"
        )
    }.flatten

    val serviceContextImports = model.services.values.collect {
      case _: ModelBuilder.ActionService =>
        List(
          "com.akkaserverless.javasdk.action.ActionCreationContext",
          "java.util.function.Function"
        )
      case _: ModelBuilder.ViewService =>
        List(
          "com.akkaserverless.javasdk.view.ViewCreationContext",
          "java.util.function.Function"
        )
    }.flatten
    val contextImports = (entityContextImports ++ serviceContextImports).toSet

    val entityCreators =
      model.entities.values.collect {
        case entity: ModelBuilder.EventSourcedEntity =>
          s"Function<EventSourcedEntityContext, ${entity.fqn.name}> create${entity.fqn.name}"
        case entity: ModelBuilder.ValueEntity =>
          s"Function<ValueEntityContext, ${entity.fqn.name}> create${entity.fqn.name}"
        case entity: ModelBuilder.ReplicatedEntity =>
          s"Function<ReplicatedEntityContext, ${entity.fqn.name}> create${entity.fqn.name}"
      }.toList

    val serviceCreators = model.services.values.collect {
      case service: ModelBuilder.ActionService =>
        s"Function<ActionCreationContext, ${service.className}> create${service.className}"
      case view: ModelBuilder.ViewService =>
        s"Function<ViewCreationContext, ${view.viewClassName}> create${view.viewClassName}"
    }.toList

    val creatorParameters = entityCreators ::: serviceCreators
    val imports =
      (List("com.akkaserverless.javasdk.AkkaServerless") ++ entityImports ++ serviceImports ++ otherImports ++ contextImports).distinct.sorted
        .map(pkg => s"import $pkg;")
        .mkString("\n")

    s"""|$managedCodeCommentString
        |
        |package $mainClassPackageName;
        |
        |$imports
        |
        |public final class AkkaServerlessFactory {
        |
        |  public static AkkaServerless withComponents(
        |      ${creatorParameters.mkString(",\n      ")}) {
        |    AkkaServerless akkaServerless = new AkkaServerless();
        |    return akkaServerless
        |      ${Syntax.indent(registrations, 6)};
        |  }
        |}""".stripMargin
  }

  private[codegen] def mainSource(
      mainClassPackageName: String,
      mainClassName: String,
      entities: Map[String, Entity],
      services: Map[String, Service]
  ): String = {

    val entityImports = entities.values.collect {
      case entity: ModelBuilder.EventSourcedEntity => entity.fqn.fullQualifiedName
      case entity: ModelBuilder.ValueEntity => entity.fqn.fullQualifiedName
      case entity: ModelBuilder.ReplicatedEntity => entity.fqn.fullQualifiedName
    }.toSeq

    val serviceImports = services.values.collect {
      case service: ModelBuilder.ActionService => service.classNameQualified
      case view: ModelBuilder.ViewService => view.classNameQualified
    }.toSeq

    val componentImports = generateImports(
      Iterable.empty,
      mainClassPackageName,
      entityImports ++ serviceImports
    )
    val entityRegistrationParameters = entities.values.collect {
      case entity: ModelBuilder.EventSourcedEntity => s"${entity.fqn.name}::new"
      case entity: ModelBuilder.ValueEntity => s"${entity.fqn.name}::new"
      case entity: ModelBuilder.ReplicatedEntity => s"${entity.fqn.name}::new"
    }.toList

    val serviceRegistrationParameters = services.values.collect {
      case service: ModelBuilder.ActionService => s"${service.className}::new"
      case view: ModelBuilder.ViewService => s"${view.viewClassName}::new"
    }.toList

    val registrationParameters = entityRegistrationParameters ::: serviceRegistrationParameters
    s"""|$generatedCodeCommentString
        |
        |package $mainClassPackageName;
        |
        |import com.akkaserverless.javasdk.AkkaServerless;
        |import org.slf4j.Logger;
        |import org.slf4j.LoggerFactory;
        |${componentImports}
        |
        |public final class ${mainClassName} {
        |
        |  private static final Logger LOG = LoggerFactory.getLogger(${mainClassName}.class);
        |
        |  public static AkkaServerless createAkkaServerless() {
        |    // The AkkaServerlessFactory automatically registers any generated Actions, Views or Entities,
        |    // and is kept up-to-date with any changes in your protobuf definitions.
        |    // If you prefer, you may remove this and manually register these components in a
        |    // `new AkkaServerless()` instance.
        |    return AkkaServerlessFactory.withComponents(
        |      ${registrationParameters.mkString(",\n      ")});
        |  }
        |
        |  public static void main(String[] args) throws Exception {
        |    LOG.info("starting the Akka Serverless service");
        |    createAkkaServerless().start();
        |  }
        |}""".stripMargin

  }

  private def disassembleClassName(fullClassName: String): (String, String) = {
    val className = fullClassName.reverse.takeWhile(_ != '.').reverse
    val packageName = fullClassName.dropRight(className.length + 1)
    packageName -> className
  }

  private[java] def qualifiedType(fullyQualifiedName: FullyQualifiedName): String =
    if (fullyQualifiedName.parent.javaMultipleFiles) fullyQualifiedName.name
    else s"${fullyQualifiedName.parent.javaOuterClassname}.${fullyQualifiedName.name}"

  private[java] def typeImport(fullyQualifiedName: FullyQualifiedName): String = {
    val name =
      if (fullyQualifiedName.parent.javaMultipleFiles) fullyQualifiedName.name
      else fullyQualifiedName.parent.javaOuterClassname
    s"${fullyQualifiedName.parent.javaPackage}.$name"
  }

  private[java] def lowerFirst(text: String): String =
    text.headOption match {
      case Some(c) => c.toLower.toString + text.drop(1)
      case None => ""
    }

  private[java] def packageAsPath(packageName: String): Path =
    Paths.get(packageName.replace(".", "/"))

  private[java] val generatedCodeCommentString: String =
    """|/* This code was generated by Akka Serverless tooling.
        | * As long as this file exists it will not be re-generated.
        | * You are free to make changes to this file.
        | */""".stripMargin

  private[java] val managedCodeCommentString: String =
    """|/* This code is managed by Akka Serverless tooling.
        | * It will be re-generated to reflect any changes to your protobuf definitions.
        | * DO NOT EDIT
        | */""".stripMargin

}
