/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.datatorrent.stram.webapp.asm;

import org.apache.xbean.asm5.Opcodes;
import org.apache.xbean.asm5.signature.SignatureReader;


/**
 * A {@link org.apache.xbean.asm5.tree.MethodNode} implementation to parse method signature as well
 *
 * @since 2.1
 */
public class MethodNode extends org.apache.xbean.asm5.tree.MethodNode
{

  public ClassSignatureVisitor typeVariableSignatureNode;
 
  public MethodSignatureVisitor signatureNode;


  public MethodNode(int access, String name, String desc, String signature, String[] exceptions)
  {
    super(Opcodes.ASM5, access, name, desc, signature, exceptions);
  }
  
  @Override
  public void visitEnd()
  {
    super.visitEnd();
    String methodString = signature != null ? signature : desc;
    SignatureReader reader = new SignatureReader(methodString);
    signatureNode = new MethodSignatureVisitor();
//    signatureNode.signature = methodString;
    signatureNode.typeV.addAll(typeVariableSignatureNode.typeV);
    reader.accept(signatureNode);
  }
  
  
  

}
