import org.scalajs.linker.interface.ModuleInitializer

enablePlugins(VitePlugin)

name := "scalajs-vite-quick-start"
scalaVersion := "2.13.8"

libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "2.2.0"

scalaJSModuleInitializers := Seq(
  ModuleInitializer
    .mainMethodWithArgs("quickstart.Main", "main")
    .withModuleID("main")
)
scalaJSLinkerConfig ~= {
  _.withModuleKind(ModuleKind.ESModule)
}

Compile / unmanagedSourceDirectories += baseDirectory.value / "vite"
