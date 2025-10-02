#!/usr/bin/env python3

import json
import sys
from pathlib import Path
from collections import defaultdict
from typing import Dict, List, Tuple
import statistics

def parse_benchmark_file(filepath: Path) -> Dict:
    """Parse a JMH benchmark JSON file"""
    try:
        with open(filepath) as f:
            return json.load(f)
    except Exception as e:
        print(f"Error reading {filepath}: {e}")
        return []

def extract_framework_name(benchmark_name: str) -> str:
    """Extract framework from benchmark name"""
    if 'eru' in benchmark_name.lower():
        return 'Eru'
    elif 'zio' in benchmark_name.lower():
        return 'ZIO'
    elif 'io' in benchmark_name.lower() and 'zio' not in benchmark_name.lower():
        return 'Cats'
    return 'Unknown'

def extract_operation_name(benchmark_name: str) -> str:
    """Extract operation name from benchmark"""
    parts = benchmark_name.split('.')
    if len(parts) >= 2:
        method = parts[-1]
        # Remove framework prefix
        for prefix in ['eru', 'zio', 'io']:
            if method.lower().startswith(prefix):
                return method[len(prefix):]
        return method
    return benchmark_name

def analyze_category(category: str, timestamp: str) -> Dict:
    """Analyze a single benchmark category"""
    filepath = Path(f"benchmark-results/{category}-{timestamp}.json")
    if not filepath.exists():
        return {}

    data = parse_benchmark_file(filepath)
    if not data:
        return {}

    results = defaultdict(lambda: defaultdict(float))

    for item in data:
        benchmark = item.get('benchmark', '')
        score = item.get('primaryMetric', {}).get('score', 0)

        framework = extract_framework_name(benchmark)
        operation = extract_operation_name(benchmark)

        results[operation][framework] = score

    return dict(results)

def calculate_ratios(results: Dict) -> Tuple[List[float], List[float]]:
    """Calculate Eru performance ratios vs other frameworks"""
    eru_vs_cats = []
    eru_vs_zio = []

    for op, scores in results.items():
        if 'Eru' in scores:
            eru_score = scores['Eru']
            if 'Cats' in scores and scores['Cats'] > 0:
                eru_vs_cats.append(eru_score / scores['Cats'])
            if 'ZIO' in scores and scores['ZIO'] > 0:
                eru_vs_zio.append(eru_score / scores['ZIO'])

    return eru_vs_cats, eru_vs_zio

def print_category_analysis(category: str, results: Dict):
    """Print analysis for a category"""
    if not results:
        return

    print(f"\n{'=' * 70}")
    print(f"📊 {category.upper().replace('-', ' ')}")
    print(f"{'=' * 70}")

    # Sort operations by Eru performance
    sorted_ops = sorted(results.items(),
                       key=lambda x: x[1].get('Eru', 0),
                       reverse=True)

    print(f"{'Operation':<30} {'Eru':>12} {'vs Cats':>10} {'vs ZIO':>10}")
    print("-" * 70)

    for op, scores in sorted_ops[:15]:  # Show top 15
        eru_score = scores.get('Eru', 0)
        if eru_score == 0:
            continue

        cats_ratio = ""
        if 'Cats' in scores and scores['Cats'] > 0:
            ratio = eru_score / scores['Cats']
            cats_ratio = f"{ratio:.1f}x"

        zio_ratio = ""
        if 'ZIO' in scores and scores['ZIO'] > 0:
            ratio = eru_score / scores['ZIO']
            zio_ratio = f"{ratio:.1f}x"

        op_name = op[:28] if len(op) > 28 else op
        print(f"{op_name:<30} {eru_score:>12.1f} {cats_ratio:>10} {zio_ratio:>10}")

def main():
    timestamp = sys.argv[1] if len(sys.argv) > 1 else "2025-09-29_06-49-54"

    print("=" * 80)
    print(f"COMPREHENSIVE BENCHMARK ANALYSIS - {timestamp}")
    print("=" * 80)

    categories = [
        "core-operations",
        "error-handling",
        "collection-operations",
        "concurrency",
        "coordination",
        "resource-management",
        "stack-safety",
        "state-management"
    ]

    all_eru_vs_cats = []
    all_eru_vs_zio = []
    category_results = {}

    for category in categories:
        results = analyze_category(category, timestamp)
        if results:
            category_results[category] = results
            print_category_analysis(category, results)

            ratios = calculate_ratios(results)
            all_eru_vs_cats.extend(ratios[0])
            all_eru_vs_zio.extend(ratios[1])

    # Print overall summary
    print("\n" + "=" * 80)
    print("EXECUTIVE SUMMARY")
    print("=" * 80)

    if all_eru_vs_cats:
        print(f"\n📈 ERU vs CATS EFFECT (across {len(all_eru_vs_cats)} comparable operations):")
        print(f"   Average:  {statistics.mean(all_eru_vs_cats):.1f}x faster")
        print(f"   Median:   {statistics.median(all_eru_vs_cats):.1f}x faster")
        print(f"   Maximum:  {max(all_eru_vs_cats):.1f}x faster")
        print(f"   Minimum:  {min(all_eru_vs_cats):.1f}x")

        # Find where Eru is slower
        slower = [r for r in all_eru_vs_cats if r < 1.0]
        if slower:
            print(f"   ⚠️  Slower in {len(slower)} operations ({len(slower)/len(all_eru_vs_cats)*100:.1f}%)")

    if all_eru_vs_zio:
        print(f"\n📈 ERU vs ZIO (across {len(all_eru_vs_zio)} comparable operations):")
        print(f"   Average:  {statistics.mean(all_eru_vs_zio):.1f}x faster")
        print(f"   Median:   {statistics.median(all_eru_vs_zio):.1f}x faster")
        print(f"   Maximum:  {max(all_eru_vs_zio):.1f}x faster")
        print(f"   Minimum:  {min(all_eru_vs_zio):.1f}x")

        slower = [r for r in all_eru_vs_zio if r < 1.0]
        if slower:
            print(f"   ⚠️  Slower in {len(slower)} operations ({len(slower)/len(all_eru_vs_zio)*100:.1f}%)")

    # Key findings
    print("\n🔍 KEY FINDINGS:")

    # Find best performance categories
    best_categories = []
    for cat, results in category_results.items():
        ratios = calculate_ratios(results)
        if ratios[0] and statistics.mean(ratios[0]) > 100:
            best_categories.append((cat, statistics.mean(ratios[0])))

    if best_categories:
        best_categories.sort(key=lambda x: x[1], reverse=True)
        print("\n   🏆 Categories where Eru dominates (>100x faster than Cats):")
        for cat, ratio in best_categories[:5]:
            print(f"      - {cat.replace('-', ' ').title()}: {ratio:.0f}x faster on average")

    # Find operations where Eru excels
    top_wins = []
    for cat, results in category_results.items():
        for op, scores in results.items():
            if 'Eru' in scores and 'Cats' in scores and scores['Cats'] > 0:
                ratio = scores['Eru'] / scores['Cats']
                if ratio > 100:
                    top_wins.append((f"{cat}/{op}", ratio))

    if top_wins:
        top_wins.sort(key=lambda x: x[1], reverse=True)
        print("\n   💫 Top 5 Performance Wins vs Cats Effect:")
        for op, ratio in top_wins[:5]:
            print(f"      - {op}: {ratio:.0f}x faster")

    print("\n💡 RECOMMENDATIONS:")
    print("   1. Run with GC profiling (-gc flag) for memory efficiency analysis")
    print("   2. Focus optimization on operations where Eru is <2x faster than ZIO")
    print("   3. Investigate any operations where Eru is slower than competitors")
    print("   4. Run with standard settings (3 warmups, 5 measurements) for final numbers")
    print("   5. Consider running individual slow benchmarks in isolation to rule out interference")

if __name__ == "__main__":
    main()