#!/usr/bin/env python3
"""Provision the two approved French test revisions through the staging workflow."""

import argparse
import hashlib
import json
import os
import re
import runpy
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPOSITORY = "urbainmorel/KWABOR"
DOCUMENT_PATHS = {
    "privacy_policy": "docs/staging-demo/confidentialite-d-essai.md",
    "terms": "docs/staging-demo/conditions-d-essai.md",
}
CONFIRMATION = "LOAD-EXACT-STAGING-TEST-DOCUMENTS"
ACTIVE_DOCUMENTS = """
select coalesce(jsonb_agg(jsonb_build_object(
    'document_type', document_type::text,
    'version', version,
    'content_url', content_url,
    'content_sha256', content_sha256,
    'effective', effective_at <= now()
) order by document_type::text), '[]'::jsonb)
from public.legal_documents
where locale = 'fr' and active and document_type in ('terms', 'privacy_policy')
""".strip()


def documents_for(commit: str) -> list[dict[str, object]]:
    if not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("Invalid document source commit")
    return [
        {
            "document_type": document_type,
            "version": f"staging-test-{commit[:12]}",
            "content_url": f"https://raw.githubusercontent.com/{REPOSITORY}/{commit}/{path}",
            "content_sha256": hashlib.sha256((ROOT / path).read_bytes()).hexdigest(),
            "effective": True,
        }
        for document_type, path in DOCUMENT_PATHS.items()
    ]


def verify_source_documents(documents: list[dict[str, object]]) -> None:
    for document in documents:
        with urllib.request.urlopen(str(document["content_url"]), timeout=15) as response:
            content = response.read(65_537)
        if len(content) > 65_536 or hashlib.sha256(content).hexdigest() != document["content_sha256"]:
            raise ValueError("Published document bytes differ from the approved commit")


def sql_json(value: object) -> str:
    return "'" + json.dumps(value, ensure_ascii=True, sort_keys=True).replace("'", "''") + "'::jsonb"


def operation_sql(
    operation: str, documents: list[dict[str, object]], planned: object = None
) -> str:
    if operation in {"plan", "verify"}:
        return f"begin read only; set local statement_timeout = '20s'; {ACTIVE_DOCUMENTS}; commit;"
    if operation != "apply" or planned not in ([], documents):
        raise ValueError("Invalid document operation or reviewed baseline")

    return f"""
    begin;
    set local lock_timeout = '5s';
    set local statement_timeout = '20s';
    lock table public.legal_documents in share row exclusive mode;
    do $documents$
    begin
        if ({ACTIVE_DOCUMENTS}) <> {sql_json(planned)} then
            raise exception 'Active French test documents changed since plan';
        end if;
        insert into public.legal_documents
            (document_type, version, locale, content_url, content_sha256, effective_at, active)
        select (doc->>'document_type')::public.legal_document_type, doc->>'version',
            'fr', doc->>'content_url', doc->>'content_sha256', now(), true
        from jsonb_array_elements({sql_json(documents)}) as source(doc)
        on conflict (document_type, locale, version) do nothing;
        if ({ACTIVE_DOCUMENTS}) <> {sql_json(documents)} then
            raise exception 'Active French test document verification failed';
        end if;
    end;
    $documents$;
    {ACTIVE_DOCUMENTS};
    commit;
    """


def execute(args: argparse.Namespace) -> None:
    commit = os.environ["EXPECTED_SHA"]
    if (
        os.environ.get("GITHUB_REPOSITORY") != REPOSITORY
        or os.environ.get("GITHUB_REF") != "refs/heads/main"
        or os.environ.get("GITHUB_SHA") != commit
        or subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip() != commit
    ):
        raise ValueError("The workflow and checkout must target the same canonical main commit")
    guard = runpy.run_path(str(ROOT / "tools/closed-beta-staging-database.py"))
    database_url = os.environ["KWABOR_STAGING_DATABASE_URL"]
    authority = guard["validate_target_authority"](
        environment=os.environ["KWABOR_ENVIRONMENT"],
        api_url=os.environ["KWABOR_SUPABASE_URL"],
        project_ref=os.environ["KWABOR_SUPABASE_PROJECT_REF"],
        production_project_ref=os.environ["KWABOR_PRODUCTION_SUPABASE_PROJECT_REF"],
        project_ref_sha256=os.environ["KWABOR_STAGING_PROJECT_REF_SHA256"],
        database_url=database_url,
    )
    guard["validate_environment_protection"](json.loads(args.environment_file.read_text(encoding="utf-8")))
    documents = documents_for(commit)
    verify_source_documents(documents)
    reference = {
        "repository": REPOSITORY,
        "commit": commit,
        "ciRunId": os.environ["VALIDATED_CI_RUN_ID"],
        "projectRefSha256": authority.project_ref_sha256,
        "documents": documents,
    }
    planned = None
    if args.operation == "apply":
        if os.environ.get("APPLY_CONFIRMATION") != CONFIRMATION or args.plan is None:
            raise ValueError("Explicit confirmation and reviewed plan required")
        plan = json.loads(args.plan.read_text(encoding="utf-8"))
        if any(plan.get(field) != value for field, value in reference.items()) or plan.get("operation") != "plan":
            raise ValueError("Plan does not match the canonical commit, staging or document revisions")
        planned = plan.get("activeDocuments")
    elif os.environ.get("APPLY_CONFIRMATION") or args.plan is not None:
        raise ValueError("Plan authority and confirmation are reserved for apply")

    fixture_runner = runpy.run_path(str(ROOT / "tools/closed-beta-staging-fixture-archive.py"))
    result = fixture_runner["run_psql"](database_url, operation_sql(args.operation, documents, planned))
    if result.returncode:
        raise ValueError("The staging transaction refused the document operation")
    active_documents = json.loads(result.stdout.strip())
    if active_documents != documents and (args.operation != "plan" or active_documents != []):
        raise ValueError("Unexpected active French terms or privacy revision")
    receipt = {**reference, "operation": args.operation, "activeDocuments": active_documents}
    args.receipt.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(f"Staging test documents {args.operation}: {len(active_documents)} exact active revisions")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("plan", "apply", "verify"))
    parser.add_argument("--environment-file", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--plan", type=Path)
    try:
        execute(parser.parse_args())
    except (KeyError, OSError, RuntimeError, ValueError, subprocess.SubprocessError):
        print("Staging test documents refused. Review the protected run and its inputs.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
