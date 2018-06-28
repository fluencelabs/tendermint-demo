/*
 * Copyright 2018 Fluence Labs Limited
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

package kvstore

trait Operation {
  def apply(root: Node, targetKey: String): Either[String, (Node, String)]
}

case class SetValueOperation(value: String) extends Operation {
  override def apply(root: Node, targetKey: String): Either[String, (Node, String)] = {
    System.out.println(s"process set value=$value")
    Right(root.add(targetKey, value), value)
  }
}

case class CopyOperation(arg: String) extends Operation {
  override def apply(root: Node, targetKey: String): Either[String, (Node, String)] = {
    System.out.println(s"process copy arg=$arg")
    root.getValue(arg)
      .toRight("Wrong argument")
      .map(value => (root.add(targetKey, value), value))
  }
}

case class IncrementOperation(arg: String) extends Operation {
  override def apply(root: Node, targetKey: String): Either[String, (Node, String)] = {
    System.out.println(s"process increment arg=$arg")
    root.getLongValue(arg)
      .map(value => (value.toString, (value + 1).toString))
      .toRight("Wrong argument")
      .map(values => (root.add(targetKey, values._1).add(arg, values._2), values._1))
  }
}

case class FactorialOperation(arg: String) extends Operation {
  override def apply(root: Node, targetKey: String): Either[String, (Node, String)] = {
    System.out.println(s"process factorial arg=$arg")
    root.getLongValue(arg)
      .filter(_ >= 0)
      .map(value => (1L to value).product.toString)
      .toRight("Wrong argument")
      .map(factorial => (root.add(targetKey, factorial), factorial))
  }
}

case class HierarchicalSumOperation(arg: String) extends Operation {
  override def apply(root: Node, targetKey: String): Either[String, (Node, String)] = {
    System.out.println(s"process hierarchical sum arg=$arg")
    root.getNode(arg)
      .flatMap(calculate)
      .map(_.toString)
      .toRight("Wrong argument")
      .map(sum => (root.add(targetKey, sum), sum))
  }

  private def calculate(node: Node): Option[Long] = {
    for {
      nodeValue <- node.longValue.orElse(Some(0L))
      childrenValues <- node.children.values.foldLeft(Option(0L))((acc, node) => acc.flatMap(x => calculate(node).map(_ + x)))
    } yield nodeValue + childrenValues
  }
}

case class SumOperation(arg1: String, arg2: String) extends Operation {
  override def apply(root: Node, targetKey: String): Either[String, (Node, String)] = {
    System.out.println(s"process sum arg1=$arg1 arg2=$arg2")
    val value = for {
      arg1Value <- root.getLongValue(arg1)
      arg2Value <- root.getLongValue(arg2)
    } yield arg1Value + arg2Value
    value
      .map(_.toString)
      .toRight("Wrong arguments")
      .map(sum => (root.add(targetKey, sum), sum))
  }
}

