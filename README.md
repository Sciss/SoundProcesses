# SoundProcesses

## statement

SoundProcesses is an extension for ScalaCollider to describe, create and manage sound processes in the Scala programming language. It is (C)opyright 2010&ndash;2013 by Hanns Holger Rutz. All rights reserved. SoundProcesses is released under the [GNU General Public License](http://github.com/Sciss/SoundProcesses/blob/master/licenses/SoundProcesses-License.txt) and comes with absolutely no warranties. To contact the author, send an email to `contact at sciss.de`.

## building

SoundProcesses builds with sbt 0.13 and Scala 2.10. The dependencies should be downloaded automatically from maven central repository.

## linking

The following dependency is necessary:

    resolvers += "Oracle Repository" at "http://download.oracle.com/maven"
    
    "de.sciss" %% "soundprocesses" % v

The current version `v` is `"1.9.1+"`.

## usage

Project is still experimental, and documentation is still missing. There is a graphical front-end [Mellite](https://github.com/Sciss/Mellite) (also experimental)...
