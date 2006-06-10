/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Common Public License 1.0 (http://opensource.org/licenses/cpl.php)
 *   which can be found in the file CPL.TXT at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

package org.clojure.runtime;

public class Binding {
public Object val;
public Binding rest;

public Binding(Object val) {
    this.val = val;
}

public Binding(Object val, Binding rest) {
    this.val = val;
    this.rest = rest;
}
}
