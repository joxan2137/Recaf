package me.coley.recaf.assemble.ast.arch;

import me.coley.recaf.assemble.ast.Named;
import me.coley.recaf.assemble.ast.Descriptor;
import me.coley.recaf.assemble.ast.Element;
import me.coley.recaf.assemble.ast.meta.Signature;

import java.util.List;

/**
 * Outlines a {@link FieldDefinition} or {@link MethodDefinition}.
 *
 * @author Matt Coley
 */
public interface MemberDefinition extends Element, Descriptor, Named {
	/**
	 * @return {@code true} if the current instance is a field.
	 */
	default boolean isField() {
		return !isMethod();
	}

	/**
	 * @return {@code true} if the current instance is a method.
	 */
	boolean isMethod();

	/**
	 * @return Member's modifiers.
	 */
	Modifiers getModifiers();

	List<Annotation> getAnnotations();

	Signature getSignature();

}
