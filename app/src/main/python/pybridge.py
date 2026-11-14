"""Bridge between the WebView (JavaScript) and the native Python (Chaquopy).

run(code) behaves like Pyodide's runPython: it executes `code` in one persistent
namespace and returns the value of the last expression. stdout/stderr are captured and
returned so the page can show them in its log.
"""
import ast
import contextlib
import io
import json
import os
import sys
import traceback

_G = {"__name__": "__pyrun__"}


def setup(root):
    scripts = os.path.join(root, "scripts")
    if scripts not in sys.path:
        sys.path.insert(0, scripts)
    os.environ.setdefault("PYTHONIOENCODING", "utf-8")


def run(code):
    out, err = io.StringIO(), io.StringIO()
    result, failure = None, None
    try:
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            tree = ast.parse(code, "<exec>", "exec")
            last = None
            if tree.body and isinstance(tree.body[-1], ast.Expr):
                last = ast.Expression(tree.body.pop().value)
            exec(compile(tree, "<exec>", "exec"), _G)
            if last is not None:
                value = eval(compile(last, "<exec>", "eval"), _G)
                result = None if value is None else str(value)
    except SystemExit:
        pass
    except BaseException:
        failure = traceback.format_exc()
    return json.dumps({"r": result, "o": out.getvalue(), "e": err.getvalue(), "x": failure})
