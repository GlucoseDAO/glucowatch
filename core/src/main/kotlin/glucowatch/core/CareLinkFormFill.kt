package glucowatch.core

import kotlinx.serialization.json.JsonPrimitive

/**
 * Fills the username and password on Medtronic's sign-in page.
 * Values are embedded as JSON strings, so a quote or slash in a password cannot break out of the script.
 * The page still has a captcha; this does not submit the form.
 */
object CareLinkFormFill {
    fun script(username: String, password: String): String {
        val user = JsonPrimitive(username).toString()
        val pass = JsonPrimitive(password).toString()
        return """
            (function() {
              var user = $user;
              var pass = $pass;
              function visible(el) {
                if (!el || el.disabled) return false;
                var style = window.getComputedStyle(el);
                if (style.display === 'none' || style.visibility === 'hidden') return false;
                var box = el.getBoundingClientRect();
                return box.width > 0 && box.height > 0;
              }
              function write(el, value) {
                if (!visible(el) || el.value) return;
                el.focus();
                var desc = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                if (desc && desc.set) desc.set.call(el, value); else el.value = value;
                el.dispatchEvent(new Event('input', {bubbles: true}));
                el.dispatchEvent(new Event('change', {bubbles: true}));
              }
              function fill(doc, selectors, value) {
                var nodes = doc.querySelectorAll(selectors);
                for (var i = 0; i < nodes.length; i++) write(nodes[i], value);
                var frames = doc.querySelectorAll('iframe');
                for (var j = 0; j < frames.length; j++) {
                  try { if (frames[j].contentDocument) fill(frames[j].contentDocument, selectors, value); } catch (e) {}
                }
              }
              fill(document, 'input#username, input[name="username"], input[name="email"], input#email, input[autocomplete="username"]', user);
              fill(document, 'input#password, input[name="password"], input[type="password"], input[autocomplete="current-password"]', pass);
            })();
        """.trimIndent()
    }
}
