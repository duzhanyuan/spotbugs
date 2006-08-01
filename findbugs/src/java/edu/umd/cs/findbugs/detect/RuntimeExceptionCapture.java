/*
 * FindBugs - Find bugs in Java programs
 * Copyright (C) 2004 Brian Goetz <briangoetz@users.sourceforge.net>
 * Copyright (C) 2004 University of Maryland
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edu.umd.cs.findbugs.detect;


import edu.umd.cs.findbugs.*;
import edu.umd.cs.findbugs.ba.*;
import java.util.*;
import org.apache.bcel.Repository;
import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.*;
;

/**
 * RuntimeExceptionCapture
 *
 * @author Brian Goetz
 * @author Bill Pugh
 * @author David Hovemeyer
 */
public class RuntimeExceptionCapture extends BytecodeScanningDetector implements Detector, StatelessDetector {
	private static final boolean DEBUG = SystemProperties.getBoolean("rec.debug");

	private BugReporter bugReporter;
	private Method method;
	private OpcodeStack stack = new OpcodeStack();
	private List<ExceptionCaught> catchList;
	private List<ExceptionThrown> throwList;

	private BugAccumulator accumulator;
	private static class ExceptionCaught {
		public String exceptionClass;
		public int startOffset, endOffset, sourcePC;
		public boolean seen = false;
		public boolean dead = false;

		public ExceptionCaught(String exceptionClass, int startOffset, int endOffset, int sourcePC) {
			this.exceptionClass = exceptionClass;
			this.startOffset = startOffset;
			this.endOffset = endOffset;
			this.sourcePC = sourcePC;
		}
	}

	private static class ExceptionThrown {
		public String exceptionClass;
		public int offset;

		public ExceptionThrown(String exceptionClass, int offset) {
			this.exceptionClass = exceptionClass;
			this.offset = offset;
		}
	}


	public RuntimeExceptionCapture(BugReporter bugReporter) {
		this.bugReporter = bugReporter;
		accumulator = new BugAccumulator(bugReporter);
	}



	@Override
         public void visitMethod(Method method) {
		this.method = method;
		if (DEBUG) {
			System.out.println("RuntimeExceptionCapture visiting " + method);
		}
		super.visitMethod(method);
		accumulator.reportAccumulatedBugs();
	}

	@Override
         public void visitCode(Code obj) {
		catchList = new ArrayList<ExceptionCaught>();
		throwList = new ArrayList<ExceptionThrown>();
                stack.resetForMethodEntry(this);

		super.visitCode(obj);

		for (ExceptionCaught caughtException : catchList) {
			Set<String> thrownSet = new HashSet<String>();
			for (ExceptionThrown thrownException : throwList) {
				if (thrownException.offset >= caughtException.startOffset
						&& thrownException.offset < caughtException.endOffset) {
					thrownSet.add(thrownException.exceptionClass);
					if (thrownException.exceptionClass.equals(caughtException.exceptionClass))
						caughtException.seen = true;
				}
			}
			int catchClauses = 0;
			if (caughtException.exceptionClass.equals("java.lang.Exception") && !caughtException.seen) {
				// Now we have a case where Exception is caught, but not thrown
				boolean rteCaught = false;
				for (ExceptionCaught otherException : catchList) {
					if (otherException.startOffset == caughtException.startOffset
							&& otherException.endOffset == caughtException.endOffset) {
						catchClauses++;
						if (otherException.exceptionClass.equals("java.lang.RuntimeException"))
							rteCaught = true;
					}
				}
				int range = caughtException.endOffset - caughtException.startOffset;
				if (!rteCaught) {
					int priority = LOW_PRIORITY + 1;
					if (range > 300) priority--;
					else if (range < 30) priority++;
					if (catchClauses > 1) priority++;
					if (thrownSet.size() > 1) priority--;
					if (caughtException.dead) priority--;
					accumulator.accumulateBug(new BugInstance(this, "REC_CATCH_EXCEPTION",
							priority)
							.addClassAndMethod(this), 
							SourceLineAnnotation.fromVisitedInstruction(getClassContext(), this, caughtException.sourcePC));
				}
			}
		}
	}

	@Override
         public void visit(CodeException obj) {
		super.visit(obj);
		int type = obj.getCatchType();
		if (type == 0) return;
		String name = getConstantPool().constantToString(getConstantPool().getConstant(type));

		ExceptionCaught caughtException =
			new ExceptionCaught(name, obj.getStartPC(), obj.getEndPC(), obj.getHandlerPC());
		catchList.add(caughtException);

		try {
			// See if the store that saves the exception object
			// is alive or dead.  We rely on the fact that javac
			// always (?) emits an ASTORE instruction to save
			// the caught exception.
			LiveLocalStoreDataflow dataflow = getClassContext().getLiveLocalStoreDataflow(this.method);
			CFG cfg = getClassContext().getCFG(method);
			Collection<BasicBlock> blockList = cfg.getBlocksContainingInstructionWithOffset(obj.getHandlerPC());
			for (BasicBlock block : blockList) {
				InstructionHandle first = block.getFirstInstruction();
				if (first != null
						&& first.getPosition() == obj.getHandlerPC()
						&& first.getInstruction() instanceof ASTORE) {
					ASTORE astore = (ASTORE) first.getInstruction();
					BitSet liveStoreSet = dataflow.getFactAtLocation(new Location(first, block));
					if (!liveStoreSet.get(astore.getIndex())) {
						// The ASTORE storing the exception object is dead
						if (DEBUG) {
							System.out.println("Dead exception store at " + first);
						}
						caughtException.dead = true;
						break;
					}
				}
			}
		} catch (DataflowAnalysisException e) {
			bugReporter.logError("Error checking for dead exception store", e);
		} catch (CFGBuilderException e) {
			bugReporter.logError("Error checking for dead exception store", e);
		}
	}

	@Override
         public void sawOpcode(int seen) {
		stack.mergeJumps(this);
		try {
			switch (seen) {
			case ATHROW:
				if (stack.getStackDepth() > 0) {
					OpcodeStack.Item item = stack.getStackItem(0);
					String signature = item.getSignature();
					if (signature != null && signature.length() > 0) {
						if (signature.startsWith("L"))
							signature = SignatureConverter.convert(signature);
						else
							signature = signature.replace('/', '.');
						throwList.add(new ExceptionThrown(signature, getPC()));
					}
				}
				break;

			case INVOKEVIRTUAL:
			case INVOKESPECIAL:
			case INVOKESTATIC:
				String className = getDottedClassConstantOperand();
				try {
					if (!className.startsWith("[")) {
						JavaClass clazz = Repository.lookupClass(className);
						Method[] methods = clazz.getMethods();
						for (Method method : methods) {
							if (method.getName().equals(getNameConstantOperand())
									&& method.getSignature().equals(getSigConstantOperand())) {
								ExceptionTable et = method.getExceptionTable();
								if (et != null) {
									String[] names = et.getExceptionNames();
									for (String name : names)
										throwList.add(new ExceptionThrown(name, getPC()));
								}
								break;
							}
						}
					}
				} catch (ClassNotFoundException e) {
					bugReporter.reportMissingClass(e);
				}
				break;
			default:
				break;
			}
		} finally {
			stack.sawOpcode(this, seen);
		}
	}

}

// vim:ts=4
